package dev.gyro.engine.session

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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

/**
 * Adapter that exposes [GyroPlayerEngine] and [PlaylistManager] as a Media3 [Player] via [SimpleBasePlayer].
 */
class MpvPlayerAdapter(
    val engine: dev.gyro.engine.api.PlayerEngine,
    val playlistManager: PlaylistManager = engine.playlist,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    var onSkipToNext: (() -> Boolean)? = null,
    var onSkipToPrevious: (() -> Boolean)? = null,
    looper: Looper = Looper.getMainLooper()
) : SimpleBasePlayer(looper) {

    private var currentArtworkBytes: ByteArray? = null

    init {
        startObservingEngine()
    }

    fun setArtworkBitmap(bitmap: Bitmap?) {
        currentArtworkBytes = if (bitmap != null) {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                stream.toByteArray()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        invalidateState()
    }

    private fun startObservingEngine() {
        coroutineScope.launch {
            launch {
                engine.state
                    .map { AdapterSessionState(it.isPlaying, it.playbackState, it.durationMs, it.speed, it.mediaTitle) }
                    .distinctUntilChanged()
                    .collect { invalidateState() }
            }
            var lastPos = 0L
            launch {
                engine.timePosMs.collect { pos ->
                    if (kotlin.math.abs(pos - lastPos) >= 1500L) {
                        lastPos = pos
                        invalidateState()
                    }
                }
            }
            launch {
                playlistManager.items.collect {
                    invalidateState()
                }
            }
            launch {
                playlistManager.currentIndex.collect {
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

        val playerState = when {
            engineState == EnginePlaybackState.IDLE -> Player.STATE_IDLE
            engineState == EnginePlaybackState.BUFFERING -> Player.STATE_BUFFERING
            engineState == EnginePlaybackState.ENDED -> Player.STATE_ENDED
            else -> Player.STATE_READY
        }

        val commands = Player.Commands.Builder()
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

        val playlistItems = playlistManager.items.value
        val currentIndex = playlistManager.currentIndex.value

        val mediaItemDataList = if (playlistItems.isNotEmpty()) {
            playlistItems.mapIndexed { index, item ->
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(item.displayName)
                    .setArtist("Gyro Player")
                if (index == currentIndex && currentArtworkBytes != null) {
                    metadataBuilder.setArtworkData(
                        currentArtworkBytes,
                        MediaMetadata.PICTURE_TYPE_FRONT_COVER
                    )
                }

                val mediaItem = MediaItem.Builder()
                    .setMediaId(item.uriString)
                    .setUri(item.uriString)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()

                val itemDurationUs = if (index == currentIndex && durationMs > 0L) {
                    Util.msToUs(durationMs)
                } else {
                    C.TIME_UNSET
                }

                MediaItemData.Builder(item.uriString)
                    .setMediaItem(mediaItem)
                    .setDurationUs(itemDurationUs)
                    .build()
            }
        } else {
            val title = snapshot.mediaTitle.ifBlank { "Gyro Player" }
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist("Gyro Player")
            if (currentArtworkBytes != null) {
                metadataBuilder.setArtworkData(
                    currentArtworkBytes,
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

        val safeCurrentIndex = if (playlistItems.isNotEmpty()) {
            currentIndex.coerceIn(0, playlistItems.lastIndex)
        } else {
            0
        }

        val positionSupplier = if (isPlaying) {
            PositionSupplier.getExtrapolating(posMs, speed)
        } else {
            PositionSupplier.getConstant(posMs)
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playerState)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setPlaylist(mediaItemDataList)
            .setCurrentMediaItemIndex(safeCurrentIndex)
            .setContentPositionMs(positionSupplier)
            .build()
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
        val currentIndex = playlistManager.currentIndex.value
        if (mediaItemIndex != currentIndex && mediaItemIndex >= 0) {
            if (mediaItemIndex > currentIndex) {
                if (onSkipToNext?.invoke() != true) {
                    playlistManager.playNext()?.let { next ->
                        engine.load(Uri.parse(next.uriString), displayTitle = next.displayName)
                    }
                }
            } else {
                if (onSkipToPrevious?.invoke() != true) {
                    playlistManager.playPrevious()?.let { prev ->
                        engine.load(Uri.parse(prev.uriString), displayTitle = prev.displayName)
                    }
                }
            }
        }
        if (positionMs != C.TIME_UNSET) {
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
