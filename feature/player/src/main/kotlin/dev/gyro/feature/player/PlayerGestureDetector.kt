package dev.gyro.feature.player

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.gyro.core.model.PlayerGestureConfig

enum class DoubleTapSide {
    LEFT,
    RIGHT
}

sealed class GestureHudState {
    data object Idle : GestureHudState()
    data class Volume(
        val percent: Int,
        val volumeRatio: Float,
        val isOverdrive: Boolean = percent > 100
    ) : GestureHudState()
    data class Brightness(val percent: Int, val brightnessRatio: Float) : GestureHudState()
    data class Seek(val targetMs: Long, val deltaMs: Long, val totalMs: Long) : GestureHudState()
    data class DoubleTap(val side: DoubleTapSide, val seconds: Int) : GestureHudState()
    data class Speed(val speed: Float) : GestureHudState()
    data class Notice(val message: String) : GestureHudState()
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
 * 9. Edge dead zone derived from the platform system gesture insets
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
    onFastForwardStart: (Float) -> Unit,
    onFastForwardEnd: () -> Unit,
    onHudStateChange: (GestureHudState) -> Unit,
    onSoftwareVolumeChange: ((Double) -> Unit)? = null,
    getSoftwareVolume: (() -> Double)? = null,
    config: PlayerGestureConfig = PlayerGestureConfig()
): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val systemGestureInsets = WindowInsets.systemGestures
    val edgeThresholdPx = edgeDeadZonePx(
        basePx = with(density) { BASE_EDGE_DEAD_ZONE.toPx() },
        systemGestureInsetPx = maxOf(
            systemGestureInsets.getLeft(density, layoutDirection),
            systemGestureInsets.getRight(density, layoutDirection)
        ).toFloat(),
        maxPx = with(density) { MAX_EDGE_DEAD_ZONE.toPx() }
    )

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
        onHudStateChange,
        onSoftwareVolumeChange,
        getSoftwareVolume
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
            onHudStateChange = onHudStateChange,
            onSoftwareVolumeChange = onSoftwareVolumeChange,
            getSoftwareVolume = getSoftwareVolume
        )
    }

    return this.playerGestures(effectiveConfig, callbacks)
}

/** Minimum edge dead zone, kept for 3-button navigation where the platform reports no gesture inset. */
private val BASE_EDGE_DEAD_ZONE = 24.dp

/**
 * Ceiling for the dead zone. Vendors occasionally report inflated gesture insets; without a ceiling
 * a single bad report would swallow the swipe area on both sides and make brightness/volume feel
 * broken, which is a worse failure than the occasional diagonal back gesture.
 */
private val MAX_EDGE_DEAD_ZONE = 48.dp

/**
 * Picks the edge dead zone used to refuse drag gestures.
 *
 * Gesture navigation reserves roughly 16 to 40dp per edge depending on vendor and 3-button
 * navigation reserves nothing, so the platform inset is authoritative whenever it is wider than the
 * base. Only drags are refused: taps and double-taps still reach the edge, because the system back
 * gesture needs horizontal movement to take over and a tap never provides it.
 */
internal fun edgeDeadZonePx(basePx: Float, systemGestureInsetPx: Float, maxPx: Float): Float {
    val safeBase = if (basePx.isNaN() || basePx < 0f) 0f else basePx
    val safeInset = if (systemGestureInsetPx.isNaN() || systemGestureInsetPx < 0f) 0f else systemGestureInsetPx
    val safeMax = if (maxPx.isNaN() || maxPx <= 0f) safeBase else maxPx
    val ceiling = maxOf(safeMax, safeBase)
    return maxOf(safeBase, safeInset).coerceAtMost(ceiling)
}
