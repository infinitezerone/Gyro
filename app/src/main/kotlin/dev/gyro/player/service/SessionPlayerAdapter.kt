package dev.gyro.player.service

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException as Media3PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.session.PlaybackSession
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The subset of [dev.gyro.engine.model.PlaybackSnapshot] rendered by [SessionPlayerAdapter.getState].
 * High-frequency diagnostics are excluded so fps/bitrate ticks do not invalidate Media3 metadata.
 */
private data class AdapterSessionState(
    val isPlaying: Boolean,
    val playbackState: EnginePlaybackState,
    val durationMs: Long,
    val speed: Float,
    val mediaTitle: String,
)

private data class PositionAnchor(
    val posMs: Long = 0L,
    val realtimeMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
)

private class PlaylistCache(
    val items: List<VideoMediaItem>,
    val currentIndex: Int,
    val durationMs: Long,
    val artworkBytes: ByteArray?,
    val mediaTitle: String,
    val fallbackUri: String?,
    val mediaItemDataList: List<SimpleBasePlayer.MediaItemData>,
) {
    fun matches(
        items: List<VideoMediaItem>,
        currentIndex: Int,
        durationMs: Long,
        artworkBytes: ByteArray?,
        mediaTitle: String,
        fallbackUri: String?,
    ): Boolean =
        this.currentIndex == currentIndex &&
            this.durationMs == durationMs &&
            this.artworkBytes === artworkBytes &&
            this.mediaTitle == mediaTitle &&
            this.fallbackUri == fallbackUri &&
            (this.items === items || this.items == items)
}

/**
 * Media3 [SimpleBasePlayer] adapter backed directly by domain-level [PlaybackSession].
 * Keeps all transport controls, queue navigation, and playback state unified between UI and
 * system media controllers without touching the concrete player engine directly.
 */
@OptIn(UnstableApi::class)
class SessionPlayerAdapter(
    private val session: PlaybackSession,
    mainDispatcher: CoroutineDispatcher,
    private val compressionDispatcher: CoroutineDispatcher,
    looper: Looper = Looper.getMainLooper(),
    private val coroutineScope: CoroutineScope = CoroutineScope(mainDispatcher + SupervisorJob()),
    private val onInvalidateStateForTest: (() -> Unit)? = null,
) : SimpleBasePlayer(looper) {

    companion object {
        private const val SEEK_DISCONTINUITY_THRESHOLD_MS = 2_000L
        private const val PAUSED_POSITION_DRIFT_THRESHOLD_MS = 500L

        private val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
    }

    internal var realtimeMsProvider: () -> Long = { SystemClock.elapsedRealtime() }

    @Volatile
    private var currentArtworkBytes: ByteArray? = null

    @Volatile
    private var artworkJob: Job? = null

    private val artworkGeneration = AtomicLong(0L)

    @Volatile
    private var anchor = PositionAnchor()

    @Volatile
    private var cachedPlaylist: PlaylistCache? = null

    init {
        startObservingSession()
    }

    private fun notifyStateInvalidated() {
        val hook = onInvalidateStateForTest
        if (hook != null) {
            hook()
        } else {
            invalidateState()
        }
    }

    fun setArtworkBitmap(bitmap: Bitmap?) {
        val gen = artworkGeneration.incrementAndGet()
        artworkJob?.cancel()
        if (bitmap == null) {
            artworkJob = null
            currentArtworkBytes = null
            notifyStateInvalidated()
            return
        }
        artworkJob = coroutineScope.launch {
            val bytes = withContext(compressionDispatcher) {
                try {
                    if (bitmap.isRecycled) {
                        null
                    } else {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        stream.toByteArray()
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (isActive && artworkGeneration.get() == gen) {
                currentArtworkBytes = bytes
                notifyStateInvalidated()
            }
        }
    }

    private fun startObservingSession() {
        val initialSnapshot = session.state.value
        anchor = PositionAnchor(
            posMs = session.timePosMs.value.coerceAtLeast(0L),
            realtimeMs = realtimeMsProvider(),
            isPlaying = initialSnapshot.isPlaying,
            speed = initialSnapshot.speed.coerceAtLeast(0.1f),
        )
        coroutineScope.launch {
            launch {
                session.state
                    .map { AdapterSessionState(it.isPlaying, it.playbackState, it.durationMs, it.speed, it.mediaTitle) }
                    .distinctUntilChanged()
                    .collect { state ->
                        anchor = PositionAnchor(
                            posMs = session.timePosMs.value.coerceAtLeast(0L),
                            realtimeMs = realtimeMsProvider(),
                            isPlaying = state.isPlaying,
                            speed = state.speed.coerceAtLeast(0.1f),
                        )
                        notifyStateInvalidated()
                    }
            }
            launch {
                session.timePosMs.collect { rawPos ->
                    val pos = rawPos.coerceAtLeast(0L)
                    val now = realtimeMsProvider()
                    val currentAnchor = anchor
                    val shouldInvalidate = if (currentAnchor.isPlaying) {
                        val elapsedMs = (now - currentAnchor.realtimeMs).coerceAtLeast(0L)
                        val extrapolatedMs = currentAnchor.posMs + (elapsedMs * currentAnchor.speed).toLong()
                        abs(pos - extrapolatedMs) >= SEEK_DISCONTINUITY_THRESHOLD_MS
                    } else {
                        abs(pos - currentAnchor.posMs) >= PAUSED_POSITION_DRIFT_THRESHOLD_MS
                    }
                    if (shouldInvalidate) {
                        val snapshot = session.state.value
                        anchor = PositionAnchor(
                            posMs = pos,
                            realtimeMs = now,
                            isPlaying = snapshot.isPlaying,
                            speed = snapshot.speed.coerceAtLeast(0.1f),
                        )
                        notifyStateInvalidated()
                    }
                }
            }
            launch {
                combine(
                    session.playlist.items,
                    session.playlist.currentIndex,
                ) { items, index ->
                    items to index
                }
                    .distinctUntilChanged()
                    .collect {
                        notifyStateInvalidated()
                    }
            }
        }
    }

    override fun getState(): State {
        val snapshot = session.state.value
        val isPlaying = snapshot.isPlaying
        val engineState = snapshot.playbackState
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val posMs = session.timePosMs.value.coerceAtLeast(0L)
        val speed = snapshot.speed.coerceAtLeast(0.1f)
        anchor = PositionAnchor(
            posMs = posMs,
            realtimeMs = realtimeMsProvider(),
            isPlaying = isPlaying,
            speed = speed,
        )

        val playerState = when (engineState) {
            EnginePlaybackState.IDLE,
            EnginePlaybackState.ERROR -> Player.STATE_IDLE
            EnginePlaybackState.BUFFERING -> Player.STATE_BUFFERING
            EnginePlaybackState.READY -> Player.STATE_READY
            EnginePlaybackState.ENDED -> Player.STATE_ENDED
        }

        val playlistItems = session.playlist.items.value
        val currentIndex = session.playlist.currentIndex.value
        val safeCurrentIndex = if (playlistItems.isNotEmpty()) {
            currentIndex.coerceIn(0, playlistItems.lastIndex)
        } else {
            0
        }
        val artworkBytes = currentArtworkBytes
        val mediaTitle = snapshot.mediaTitle
        val fallbackUri = session.currentUri.value?.toString()

        val mediaItemDataList = getOrBuildMediaItemDataList(
            playlistItems = playlistItems,
            safeCurrentIndex = safeCurrentIndex,
            durationMs = durationMs,
            artworkBytes = artworkBytes,
            mediaTitle = mediaTitle,
            fallbackUri = fallbackUri,
        )

        val positionSupplier = if (isPlaying && playerState == Player.STATE_READY) {
            PositionSupplier.getExtrapolating(posMs, speed)
        } else {
            PositionSupplier.getConstant(posMs)
        }

        val stateBuilder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playerState)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setPlaylist(mediaItemDataList)
            .setCurrentMediaItemIndex(safeCurrentIndex)
            .setContentPositionMs(positionSupplier)
        if (engineState == EnginePlaybackState.ERROR) {
            stateBuilder.setPlayerError(
                Media3PlaybackException(
                    snapshot.error?.message ?: "Playback failed",
                    snapshot.error,
                    Media3PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
            )
        } else {
            stateBuilder.setPlayerError(null)
        }
        return stateBuilder.build()
    }

    private fun getOrBuildMediaItemDataList(
        playlistItems: List<VideoMediaItem>,
        safeCurrentIndex: Int,
        durationMs: Long,
        artworkBytes: ByteArray?,
        mediaTitle: String,
        fallbackUri: String?,
    ): List<MediaItemData> {
        val existing = cachedPlaylist
        if (existing != null &&
            existing.matches(playlistItems, safeCurrentIndex, durationMs, artworkBytes, mediaTitle, fallbackUri)
        ) {
            return existing.mediaItemDataList
        }

        val built = if (playlistItems.isNotEmpty()) {
            playlistItems.mapIndexed { index, item ->
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(item.displayName)
                    .setArtist("Gyro Player")
                if (index == safeCurrentIndex && artworkBytes != null) {
                    metadataBuilder.setArtworkData(
                        artworkBytes,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                    )
                }

                val mediaItem = MediaItem.Builder()
                    .setMediaId(item.uriString)
                    .setUri(item.uriString)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()

                val itemDurationUs = if (index == safeCurrentIndex && durationMs > 0L) {
                    Util.msToUs(durationMs)
                } else {
                    C.TIME_UNSET
                }

                MediaItemData.Builder("${index}_${item.uriString}")
                    .setMediaItem(mediaItem)
                    .setDurationUs(itemDurationUs)
                    .build()
            }
        } else {
            val title = mediaTitle.ifBlank { "Gyro Player" }
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist("Gyro Player")
            if (artworkBytes != null) {
                metadataBuilder.setArtworkData(
                    artworkBytes,
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                )
            }
            val mediaId = fallbackUri ?: "current_media"
            val itemBuilder = MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadataBuilder.build())
            if (fallbackUri != null) {
                itemBuilder.setUri(fallbackUri)
            }
            listOf(
                MediaItemData.Builder("0_$mediaId")
                    .setMediaItem(itemBuilder.build())
                    .setDurationUs(if (durationMs > 0L) Util.msToUs(durationMs) else C.TIME_UNSET)
                    .build()
            )
        }

        cachedPlaylist = PlaylistCache(
            items = playlistItems,
            currentIndex = safeCurrentIndex,
            durationMs = durationMs,
            artworkBytes = artworkBytes,
            mediaTitle = mediaTitle,
            fallbackUri = fallbackUri,
            mediaItemDataList = built,
        )
        return built
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            session.play()
        } else {
            session.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        session.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val items = session.playlist.items.value
        val currentIndex = session.playlist.currentIndex.value
        when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                seekCommand == Player.COMMAND_SEEK_TO_NEXT -> {
                session.navigate(PlaylistStep.Next)
            }
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS -> {
                session.navigate(PlaylistStep.Previous)
            }
            seekCommand != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM &&
                seekCommand != Player.COMMAND_SEEK_BACK &&
                seekCommand != Player.COMMAND_SEEK_FORWARD &&
                mediaItemIndex != C.INDEX_UNSET &&
                mediaItemIndex in items.indices &&
                mediaItemIndex != currentIndex -> {
                session.navigate(PlaylistStep.AtIndex(mediaItemIndex))
            }
            positionMs != C.TIME_UNSET -> {
                session.seekTo(positionMs.coerceAtLeast(0L))
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        session.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        artworkJob?.cancel()
        artworkJob = null
        coroutineScope.cancel()
        return Futures.immediateVoidFuture()
    }

    internal fun buildStateForTest(): State = getState()

    internal fun setPlayWhenReadyForTest(playWhenReady: Boolean) {
        handleSetPlayWhenReady(playWhenReady)
    }

    internal fun seekForTest(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) {
        handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    internal fun setSpeedForTest(speed: Float) {
        handleSetPlaybackParameters(PlaybackParameters(speed))
    }

    internal fun releaseForTest() {
        handleRelease()
    }
}
