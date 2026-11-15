package dev.gyro.feature.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.gesture.playerGestures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Builds the gesture-handling [Modifier] for the player surface and owns the zoom-animation
 * lifecycle that backs it: an in-flight zoom reset/settle coroutine must be cancelled when the
 * orientation flips or the file switches, so those two reset effects live here next to the
 * callbacks that start the animation instead of in the screen body.
 *
 * Must be called unconditionally on every recomposition - the reset effects run in PiP too,
 * even when the caller drops the returned Modifier there.
 */
@Composable
internal fun rememberPlayerGestureModifier(
    context: Context,
    isLandscape: Boolean,
    isPipActive: Boolean = false,
    activeUri: android.net.Uri?,
    playerState: PlayerState,
    isPlaying: Boolean,
    engine: PlayerEngine,
    durationMs: Long,
    speed: Float,
    scope: CoroutineScope,
    zoomState: PlayerZoomState,
    scrubState: PlayerScrubState,
    containerSize: IntSize,
    composeVideoBounds: Rect?,
    viewModel: PlayerViewModel,
    gestureConfig: PlayerGestureConfig,
): Modifier {
    var resetJob by remember { mutableStateOf<Job?>(null) }
    var priorSpeedBeforeBoost by remember { mutableStateOf<Float?>(null) }

    // Reset zoom state on orientation change so portrait pan offsets don't displace video in landscape
    // Ignored while PiP is active so floating window dimensions or restore transitions don't discard zoom.
    LaunchedEffect(isLandscape, isPipActive) {
        if (!isPipActive) {
            resetJob?.cancel()
            resetJob = null
            zoomState.reset()
        }
    }

    // A file switch must start from the identity transform
    LaunchedEffect(activeUri) {
        resetJob?.cancel()
        resetJob = null
        zoomState.reset()
    }

    return Modifier.playerGestures(
        context = context,
        isLocked = playerState.isLocked,
        isPlaying = isPlaying,
        currentPosMs = { engine.timePosMs.value },
        durationMs = { durationMs },
        scrubState = scrubState,
        zoomScale = { zoomState.scale },
        onZoomChange = { zoom, pan, centroid, isPinch ->
            resetJob?.cancel()
            resetJob = null
            val vpSize = containerSize.toSize()
            zoomState.onGesture(
                zoomChange = zoom,
                panDelta = pan,
                centroid = centroid,
                viewportSize = vpSize,
                contentBounds = composeVideoBounds,
                isPinch = isPinch
            )
        },
        onResetZoom = {
            resetJob?.cancel()
            resetJob = scope.launch {
                val vpSize = containerSize.toSize()
                zoomState.animateReset(vpSize, composeVideoBounds)
            }
        },
        onSettleZoom = {
            resetJob?.cancel()
            resetJob = scope.launch {
                val vpSize = containerSize.toSize()
                zoomState.animateSettle(vpSize, composeVideoBounds)
            }
        },
        onSingleTap = {
            playerState.showControls = !playerState.showControls
            if (playerState.showControls) {
                playerState.resetControlsTimeout()
            }
        },
        onDoubleTapSeek = { deltaMs ->
            engine.seekRelative(deltaMs)
        },
        onDoubleTapPlayPause = {
            engine.togglePlayPause()
        },
        onFastForwardStart = { targetSpeed ->
            priorSpeedBeforeBoost = speed
            engine.setSpeed(targetSpeed)
            playerState.gestureHudState = GestureHudState.Speed(targetSpeed)
        },
        onFastForwardEnd = {
            val restoreSpeed = priorSpeedBeforeBoost ?: 1.0f
            engine.setSpeed(restoreSpeed)
            priorSpeedBeforeBoost = null
            playerState.gestureHudState = GestureHudState.Idle
        },
        onHudStateChange = { newState ->
            playerState.gestureHudState = newState
        },
        onSoftwareVolumeChange = { gain ->
            engine.audio.setSoftwareVolume(gain)
        },
        getSoftwareVolume = {
            engine.state.value.softwareVolume
        },
        onSeekTo = { targetMs ->
            // Commit the gesture seek only. Control-layer visibility is owned by the
            // auto-hide timer in PlayerState, not by gesture callbacks.
            engine.seekTo(targetMs)
        },
        onPlayPrevious = {
            viewModel.playPrevious()
        },
        onPlayNext = {
            viewModel.playNext()
        },
        videoBounds = { composeVideoBounds },
        config = gestureConfig
    )
}
