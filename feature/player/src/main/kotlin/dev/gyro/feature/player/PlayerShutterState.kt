package dev.gyro.feature.player

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.IntSize
import dev.gyro.engine.api.session.PlaybackSessionState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.trace.SwitchTrace
import kotlinx.coroutines.delay

/**
 * Owns the black "shutter" that covers the video until a correctly shaped frame is on screen.
 *
 * This used to be three `remember`s and a 40-line `LaunchedEffect` inside `PlayerScreen`. It is
 * screen business logic - when a frame is trustworthy enough to reveal - so it belongs to a holder
 * that can be reasoned about (and tested) without composing a 1400-line screen.
 *
 * Reveal rules, in order:
 * 1. Switching sessions or no first frame yet: stay covered.
 * 2. [ShutterRevealPolicy] says the frame is correctly shaped: wait two frames, then reveal.
 * 3. Otherwise wait up to [REVEAL_TIMEOUT_MS]; a black screen is worse than a transiently
 *    mis-shaped frame, so reveal anyway.
 */
@Stable
class PlayerShutterState {

    var isReady by mutableStateOf(false)
        private set

    var timedOut by mutableStateOf(false)
        private set

    /** Whether the surface should be hidden right now. */
    fun covers(
        activeUri: Uri?,
        playbackState: dev.gyro.engine.model.PlaybackState,
        isPipLayout: Boolean,
        firstFrameRendered: Boolean,
        isSwitching: Boolean,
    ): Boolean = !isPipLayout &&
        activeUri != null &&
        playbackState != dev.gyro.engine.model.PlaybackState.ERROR &&
        (!firstFrameRendered || !isReady || isSwitching) &&
        !timedOut

    /**
     * One evaluation pass. [awaitFrame] is injected so a JVM test can advance without a frame clock.
     */
    suspend fun evaluate(
        isSwitching: Boolean,
        firstFrameRendered: Boolean,
        geometryReady: Boolean,
        videoAspect: Float,
        videoWidth: Int,
        videoHeight: Int,
        resizeMode: ResizeMode,
        containerSize: IntSize,
        bufferSize: IntSize,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ) {
        SwitchTrace.log(
            "SHUTTER_EVALUATE",
            "firstFrame=$firstFrameRendered, geometryReady=$geometryReady, " +
                "containerSize=$containerSize, buffer=$bufferSize, aspect=$videoAspect, " +
                "isSwitching=$isSwitching"
        )
        if (isSwitching || !firstFrameRendered) {
            isReady = false
            return
        }
        val decision = ShutterRevealPolicy.evaluate(
            firstFrameRendered = firstFrameRendered,
            geometryReady = geometryReady,
            videoAspect = videoAspect,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            resizeMode = resizeMode,
            containerWidth = containerSize.width,
            containerHeight = containerSize.height,
            bufferWidth = bufferSize.width,
            bufferHeight = bufferSize.height
        )
        if (decision == ShutterRevealPolicy.Decision.Reveal) {
            awaitFrame()
            awaitFrame()
            SwitchTrace.end("SHUTTER_REVEALED", "aspect=$videoAspect, containerSize=$containerSize")
            isReady = true
            timedOut = false
            return
        }
        isReady = false
        delay(REVEAL_TIMEOUT_MS)
        // Force reveal after the deadline no matter which gate is stuck.
        SwitchTrace.log(
            "SHUTTER_TIMEOUT_EXPIRED",
            "firstFrame=$firstFrameRendered, geometry=$geometryReady"
        )
        timedOut = true
        isReady = true
    }

    private companion object {
        const val REVEAL_TIMEOUT_MS = 1_500L
    }
}

@Composable
fun rememberPlayerShutterState(
    activeUri: Uri?,
    sessionState: PlaybackSessionState,
    firstFrameRendered: Boolean,
    geometryReady: Boolean,
    videoAspect: Float,
    videoWidth: Int,
    videoHeight: Int,
    resizeMode: ResizeMode,
    containerSize: IntSize,
    bufferSize: IntSize,
): PlayerShutterState {
    val state = remember(activeUri, sessionState) { PlayerShutterState() }
    LaunchedEffect(
        activeUri,
        sessionState,
        firstFrameRendered,
        geometryReady,
        containerSize,
        videoAspect,
        bufferSize,
    ) {
        state.evaluate(
            isSwitching = sessionState is PlaybackSessionState.Switching,
            firstFrameRendered = firstFrameRendered,
            geometryReady = geometryReady,
            videoAspect = videoAspect,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            resizeMode = resizeMode,
            containerSize = containerSize,
            bufferSize = bufferSize,
        )
    }
    return state
}
