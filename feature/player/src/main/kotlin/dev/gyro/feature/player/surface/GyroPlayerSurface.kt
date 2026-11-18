package dev.gyro.feature.player.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.model.computeDisplayFittedBufferSize

/**
 * Jetpack Compose wrapper for [GyroSurfaceView].
 * Communicates strictly against the `:core:engine-api` [RenderController] contract.
 */
@Composable
fun GyroPlayerSurface(
    modifier: Modifier = Modifier,
    renderController: RenderController? = null,
    isHdr: Boolean = false,
    videoDimensions: VideoDimensions = VideoDimensions(),
    videoFrameRate: Float = 0f,
    pipTransitionActive: Boolean = false,
    onAppliedBufferSizeChanged: ((IntSize) -> Unit)? = null,
    onSurfaceViewAvailable: ((GyroSurfaceView) -> Unit)? = null
) {
    val context = LocalContext.current
    val surfaceView = remember {
        GyroSurfaceView(context).apply {
            this.renderController = renderController
            onSurfaceViewAvailable?.invoke(this)
        }
    }
    LaunchedEffect(surfaceView) {
        onSurfaceViewAvailable?.invoke(surfaceView)
    }

    SideEffect {
        surfaceView.pipTransitionActive = pipTransitionActive
    }

    // The panel's HDR ability, read once and handed over as a whole table: the engine has to know
    // which HDR types the panel understands, not merely that it understands "some HDR".
    val displayCapabilities = remember(context) { context.displayHdrCapabilities() }

    LaunchedEffect(renderController, displayCapabilities) {
        renderController?.setDisplayCapabilities(displayCapabilities)
    }

    LaunchedEffect(isHdr, displayCapabilities) {
        surfaceView.setHdrOutput(isHdr && displayCapabilities.supportsHdr)
    }

    DisposableEffect(renderController) {
        surfaceView.renderController = renderController
        onDispose {
            surfaceView.releaseSurfaceOwnership(renderController)
        }
    }

    LaunchedEffect(surfaceView) {
        snapshotFlow { IntSize(surfaceView.appliedBufferWidth, surfaceView.appliedBufferHeight) }
            .collect { onAppliedBufferSizeChanged?.invoke(it) }
    }

    LaunchedEffect(videoFrameRate) {
        SurfaceLog.i(
            "GyroPlayerSurface",
            "LaunchedEffect(frameRate=$videoFrameRate), renderController=$renderController"
        )
        surfaceView.applyContentFrameRate(videoFrameRate)
    }

    LaunchedEffect(videoDimensions.width, videoDimensions.height) {
        if (videoDimensions.width <= 0 || videoDimensions.height <= 0) return@LaunchedEffect
        val (displayW, displayH) = resolvePhysicalDisplaySize(context)
        val (targetW, targetH) = computeDisplayFittedBufferSize(
            videoWidth = videoDimensions.width,
            videoHeight = videoDimensions.height,
            displayWidth = displayW,
            displayHeight = displayH
        )
        surfaceView.lockBufferSizeToVideo(targetW, targetH)
    }

    AndroidView(
        factory = {
            if (videoDimensions.width > 0 && videoDimensions.height > 0) {
                val (displayW, displayH) = resolvePhysicalDisplaySize(context)
                val (targetW, targetH) = computeDisplayFittedBufferSize(
                    videoWidth = videoDimensions.width,
                    videoHeight = videoDimensions.height,
                    displayWidth = displayW,
                    displayHeight = displayH
                )
                surfaceView.lockBufferSizeToVideo(targetW, targetH)
            }
            surfaceView
        },
        update = { view ->
            view.renderController = renderController
        },
        modifier = modifier
    )
}
