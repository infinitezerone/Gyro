package dev.gyro.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Jetpack Compose wrapper for [GyroSurfaceView].
 * Native MPV handles hardware zoom and pan directly in GPU shaders on the EGL render thread,
 * avoiding SurfaceView scaling and SurfaceFlinger BLAST transaction locks on the UI thread.
 */
@Composable
fun GyroPlayerSurface(
    modifier: Modifier = Modifier,
    engine: PlayerEngine? = null,
    videoDimensions: VideoDimensions? = null,
    videoFrameRate: Float? = null,
    pipTransitionActive: Boolean = false,
    onAppliedBufferSizeChanged: ((IntSize) -> Unit)? = null,
    onSurfaceViewAvailable: ((GyroSurfaceView) -> Unit)? = null
) {
    val context = LocalContext.current
    val surfaceView = remember {
        GyroSurfaceView(context).apply {
            this.engine = engine
            onSurfaceViewAvailable?.invoke(this)
        }
    }
    LaunchedEffect(surfaceView) {
        onSurfaceViewAvailable?.invoke(surfaceView)
    }

    SideEffect {
        surfaceView.pipTransitionActive = pipTransitionActive
    }

    // Tell the engine up front whether the panel can present HDR. Without it mpv is told to
    // output PQ on an SDR display, where it skips tone-mapping and the picture washes out.
    LaunchedEffect(engine) {
        engine?.geometry?.setDisplayHdrCapable(context.displaySupportsHdr())
    }

    val isHdr by (engine?.geometry?.isHdr ?: remember { MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(isHdr) {
        val canHdr = isHdr && context.displaySupportsHdr()
        surfaceView.setHdrOutput(canHdr)
    }

    DisposableEffect(engine) {
        surfaceView.engine = engine
        onDispose {
            surfaceView.releaseSurfaceOwnership(engine)
        }
    }

    LaunchedEffect(surfaceView) {
        snapshotFlow { IntSize(surfaceView.appliedBufferWidth, surfaceView.appliedBufferHeight) }
            .collect { onAppliedBufferSizeChanged?.invoke(it) }
    }

    val observedFrameRate by (engine?.geometry?.videoFrameRate ?: remember { MutableStateFlow(0f) }).collectAsState()
    val effectiveFrameRate = videoFrameRate ?: observedFrameRate
    LaunchedEffect(effectiveFrameRate) {
        EngineLog.i("GyroPlayerSurface", "LaunchedEffect(frameRate=$effectiveFrameRate), engine=$engine")
        surfaceView.applyContentFrameRate(effectiveFrameRate)
    }

    // Pin the buffer to the physical display resolution fitted to the video's aspect ratio.
    // From then on window resizes (including PiP entry/exit) never touch the BufferQueue again;
    // low-res videos still get full screen-resolution FBOs for Anime4K shaders and ASS subtitles,
    // while same-aspect playlist switches reuse the exact same BufferQueue without re-locking.
    val observedDimensions: VideoDimensions by (engine?.geometry?.videoDimensions ?: remember { MutableStateFlow(VideoDimensions()) }).collectAsState(initial = VideoDimensions())
    val effectiveDimensions = videoDimensions ?: observedDimensions
    LaunchedEffect(effectiveDimensions.width, effectiveDimensions.height) {
        if (effectiveDimensions.width <= 0 || effectiveDimensions.height <= 0) return@LaunchedEffect
        val (displayW, displayH) = resolvePhysicalDisplaySize(context)
        val (targetW, targetH) = computeDisplayFittedBufferSize(
            videoWidth = effectiveDimensions.width,
            videoHeight = effectiveDimensions.height,
            displayWidth = displayW,
            displayHeight = displayH
        )
        surfaceView.lockBufferSizeToVideo(targetW, targetH)
    }

    AndroidView(
        factory = {
            if (effectiveDimensions.width > 0 && effectiveDimensions.height > 0) {
                val (displayW, displayH) = resolvePhysicalDisplaySize(context)
                val (targetW, targetH) = computeDisplayFittedBufferSize(
                    videoWidth = effectiveDimensions.width,
                    videoHeight = effectiveDimensions.height,
                    displayWidth = displayW,
                    displayHeight = displayH
                )
                surfaceView.lockBufferSizeToVideo(targetW, targetH)
            }
            surfaceView
        },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}
