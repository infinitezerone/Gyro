package dev.gyro.feature.player.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.model.computeDisplayFittedBufferSize
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Jetpack Compose wrapper for [GyroSurfaceView].
 * Communicates strictly against the `:core:engine-api` [PlayerEngine] contract.
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

    // Tell the engine up front whether the panel can present HDR.
    LaunchedEffect(engine) {
        engine?.geometry?.setDisplayHdrCapable(context.displaySupportsHdr())
    }

    val isHdr by (engine?.geometry?.isHdr ?: remember { MutableStateFlow(false) }).collectAsStateWithLifecycle()
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

    val observedFrameRate by (engine?.geometry?.videoFrameRate ?: remember { MutableStateFlow(0f) }).collectAsStateWithLifecycle()
    val effectiveFrameRate = videoFrameRate ?: observedFrameRate
    LaunchedEffect(effectiveFrameRate) {
        SurfaceLog.i("GyroPlayerSurface", "LaunchedEffect(frameRate=$effectiveFrameRate), engine=$engine")
        surfaceView.applyContentFrameRate(effectiveFrameRate)
    }

    val observedDimensions: VideoDimensions by (engine?.geometry?.videoDimensions ?: remember { MutableStateFlow(VideoDimensions()) }).collectAsStateWithLifecycle()
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
