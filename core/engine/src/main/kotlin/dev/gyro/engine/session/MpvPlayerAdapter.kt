package dev.gyro.engine.session

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException as Media3PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The subset of the playback snapshot that [MpvPlayerAdapter.getState] actually renders. Diagnostic
 * fields (fps, dropped frames, bitrate) are excluded so their high-frequency ticks do not invalidate
 * the session state and rebuild the whole playlist.
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
    val mediaItemDataList: List<SimpleBasePlayer.MediaItemData>,
) {
    fun matches(
        items: List<VideoMediaItem>,
        currentIndex: Int,
        durationMs: Long,
        artworkBytes: ByteArray?,
        mediaTitle: String,
    ): Boolean =
        this.currentIndex == currentIndex &&
            this.durationMs == durationMs &&
            this.artworkBytes === artworkBytes &&
            this.mediaTitle == mediaTitle &&
            (this.items === items || this.items == items)
}

/**
 * Adapter that exposes [PlayerEngine] and its [Playlist] as a Media3 [Player] via [SimpleBasePlayer].
 */
class MpvPlayerAdapter(
    private val engine: PlayerEngine,
    private val playlist: Playlist = engine.playlist,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val onSkipToNext: (() -> Boolean)? = null,
    private val onSkipToPrevious: (() -> Boolean)? = null,
    looper: Looper = Looper.getMainLooper(),
    private val onSelectAtIndex: ((Int) -> Boolean)? = null,
    private val compressionDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
                Player.COMMAND_GET_METADATA
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
        startObservingEngine()
    }

    fun setArtworkBitmap(bitmap: Bitmap?) {
        val gen = artworkGeneration.incrementAndGet()
        artworkJob?.cancel()
        if (bitmap == null) {
            artworkJob = null
            currentArtworkBytes = null
            invalidateState()
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
                invalidateState()
            }
        }
    }

    private fun startObservingEngine() {
        val initialSnapshot = engine.state.value
        anchor = PositionAnchor(
            posMs = engine.timePosMs.value.coerceAtLeast(0L),
            realtimeMs = realtimeMsProvider(),
            isPlaying = initialSnapshot.isPlaying,
            speed = initialSnapshot.speed.coerceAtLeast(0.1f),
        )
        coroutineScope.launch {
            launch {
                engine.state
                    .map { AdapterSessionState(it.isPlaying, it.playbackState, it.durationMs, it.speed, it.mediaTitle) }
                    .distinctUntilChanged()
                    .collect { session ->
                        anchor = PositionAnchor(
                            posMs = engine.timePosMs.value.coerceAtLeast(0L),
                            realtimeMs = realtimeMsProvider(),
                            isPlaying = session.isPlaying,
                            speed = session.speed.coerceAtLeast(0.1f),
                        )
                        invalidateState()
                    }
            }
            launch {
                engine.timePosMs.collect { rawPos ->
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
                        val snapshot = engine.state.value
                        anchor = PositionAnchor(
                            posMs = pos,
                            realtimeMs = now,
                            isPlaying = snapshot.isPlaying,
                            speed = snapshot.speed.coerceAtLeast(0.1f),
                        )
                        invalidateState()
                    }
                }
            }
            launch {
                playlist.items.collect {
                    invalidateState()
                }
            }
            launch {
                playlist.currentIndex.collect {
                    invalidateState()
                }
            }
        }
    }

    override fun getState(): State {
        val snapshot = engine.state.value
        val isPlaying = snapshot.isPlaying
        val engineState = snapshot.playbackState
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val posMs = engine.timePosMs.value.coerceAtLeast(0L)
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
            EnginePlaybackState.ENDED -> Player.STATE_ENDED
            EnginePlaybackState.READY -> Player.STATE_READY
        }

        val playlistItems = playlist.items.value
        val currentIndex = playlist.currentIndex.value
        val safeCurrentIndex = if (playlistItems.isNotEmpty()) {
            currentIndex.coerceIn(0, playlistItems.lastIndex)
        } else {
            0
        }
        val artworkBytes = currentArtworkBytes
        val mediaTitle = snapshot.mediaTitle

        val mediaItemDataList = getOrBuildMediaItemDataList(
            playlistItems = playlistItems,
            safeCurrentIndex = safeCurrentIndex,
            durationMs = durationMs,
            artworkBytes = artworkBytes,
            mediaTitle = mediaTitle,
        )

        val positionSupplier = if (isPlaying) {
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
                    Media3PlaybackException.ERROR_CODE_UNSPECIFIED
                )
            )
        }
        return stateBuilder.build()
    }

    private fun getOrBuildMediaItemDataList(
        playlistItems: List<VideoMediaItem>,
        safeCurrentIndex: Int,
        durationMs: Long,
        artworkBytes: ByteArray?,
        mediaTitle: String,
    ): List<MediaItemData> {
        val existing = cachedPlaylist
        if (existing != null &&
            existing.matches(playlistItems, safeCurrentIndex, durationMs, artworkBytes, mediaTitle)
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
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
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
                    MediaMetadata.PICTURE_TYPE_FRONT_COVER
                )
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId("current_media")
                .setMediaMetadata(metadataBuilder.build())
                .build()
            listOf(
                MediaItemData.Builder("current_media")
                    .setMediaItem(mediaItem)
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
            mediaItemDataList = built,
        )
        return built
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            engine.play()
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val currentIndex = playlist.currentIndex.value
        val itemChanged = mediaItemIndex >= 0 && mediaItemIndex != currentIndex
        if (itemChanged) {
            // Item transitions belong to the host (the session controller), which owns resuming,
            // progress persistence and the queue cursor. Moving the cursor here as well is what
            // produced three different implementations of "next video".
            if (onSelectAtIndex != null) {
                onSelectAtIndex.invoke(mediaItemIndex)
            } else if (mediaItemIndex > currentIndex) {
                onSkipToNext?.invoke()
            } else {
                onSkipToPrevious?.invoke()
            }
        } else if (positionMs != C.TIME_UNSET) {
            engine.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        engine.setSpeed(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        coroutineScope.cancel()
        return Futures.immediateVoidFuture()
    }
}
