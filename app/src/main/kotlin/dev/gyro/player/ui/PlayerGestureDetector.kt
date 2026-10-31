package dev.gyro.player.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

enum class DoubleTapSide {
    LEFT,
    RIGHT
}

sealed class GestureHudState {
    data object Idle : GestureHudState()
    data class Volume(val percent: Int, val volumeRatio: Float) : GestureHudState()
    data class Brightness(val percent: Int, val brightnessRatio: Float) : GestureHudState()
    data class Seek(val targetMs: Long, val deltaMs: Long, val totalMs: Long) : GestureHudState()
    data class DoubleTap(val side: DoubleTapSide, val seconds: Int) : GestureHudState()
    data class Speed(val speed: Float) : GestureHudState()
}

/**
 * Benchmark-aligned player gesture detector backed by [PlayerGestureNode]:
 * 1. Two-finger pinch-to-zoom (0.5x - 5.0x) and pan (zero pointer conflict with single-touch)
 * 2. Left vertical swipe: Brightness with edge vertical slider
 * 3. Right vertical swipe: Volume with edge vertical slider
 * 4. Horizontal swipe: Central delta seek card with PlayerScrubState
 * 5. Double-tap left/right: 10s accumulated leap with YouTube pills
 * 6. Double-tap when zoomed: Resets zoom to 1.0x
 * 7. Long-press: 2.0x temporary fast-forward
 * 8. Single-finger drag when zoomed: Pans the zoomed video content smoothly
 * 9. System edge gesture exclusion (24dp)
 * 10. Touch lock support
 * 11. Custom MX Player configurations (sensitivity, swapping sides, disabling individual gestures)
 */
@Composable
fun Modifier.playerGestures(
    context: Context,
    isLocked: Boolean,
    isPlaying: Boolean,
    currentPosMs: () -> Long,
    durationMs: () -> Long,
    scrubState: PlayerScrubState,
    zoomScale: () -> Float,
    onZoomChange: (zoom: Float, pan: Offset, centroid: Offset, isPinch: Boolean) -> Unit,
    onResetZoom: () -> Unit,
    onSettleZoom: () -> Unit,
    onSingleTap: () -> Unit,
    onDoubleTapSeek: (Long) -> Unit,
    onDoubleTapPlayPause: () -> Unit,
    onFastForwardStart: () -> Unit,
    onFastForwardEnd: () -> Unit,
    onHudStateChange: (GestureHudState) -> Unit,
    config: PlayerGestureConfig = PlayerGestureConfig()
): Modifier {
    val density = LocalDensity.current
    val edgeThresholdPx = with(density) { 24.dp.toPx() }

    val effectiveConfig = remember(config, isLocked, edgeThresholdPx) {
        config.copy(
            isLocked = isLocked,
            edgeExclusionPx = edgeThresholdPx
        )
    }

    val callbacks = remember(
        context,
        scrubState,
        currentPosMs,
        durationMs,
        zoomScale,
        onZoomChange,
        onResetZoom,
        onSettleZoom,
        onSingleTap,
        onDoubleTapSeek,
        onDoubleTapPlayPause,
        onFastForwardStart,
        onFastForwardEnd,
        onHudStateChange
    ) {
        DefaultPlayerGestureCallbacks(
            context = context,
            currentPosMs = currentPosMs,
            durationMs = durationMs,
            scrubState = scrubState,
            zoomScale = zoomScale,
            onZoomChange = onZoomChange,
            onResetZoom = onResetZoom,
            onSettleZoom = onSettleZoom,
            onSingleTap = onSingleTap,
            onDoubleTapSeek = onDoubleTapSeek,
            onDoubleTapPlayPause = onDoubleTapPlayPause,
            onFastForwardStart = onFastForwardStart,
            onFastForwardEnd = onFastForwardEnd,
            onHudStateChange = onHudStateChange
        )
    }

    return this.playerGestures(effectiveConfig, callbacks)
}
