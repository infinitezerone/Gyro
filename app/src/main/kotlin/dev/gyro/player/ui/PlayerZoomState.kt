package dev.gyro.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin

/**
 * Anchored zoom & pan math model,
 * strictly maintaining the touch centroid as the anchor across all scales (both > 1f and < 1f).
 *
 * Separates Screen/Viewport coordinates from Content coordinates:
 * - Content Point: screenPoint / scale + offset
 * - Screen Point: (contentPoint - offset) * scale
 * - graphicsLayer translation: -offset * scale
 * - graphicsLayer transformOrigin: TransformOrigin(0f, 0f) for strict coordinate alignment
 */
class PlayerZoomState(
    initialScale: Float = 1.0f,
    val minScale: Float = 0.5f,
    val maxScale: Float = 5.0f
) {
    var scale by mutableFloatStateOf(initialScale)
        private set

    /** Viewport top-left origin in content coordinate space */
    var contentOffset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean
        get() = scale > 1.05f

    /**
     * Executes anchored zoom transformation:
     * Maintains the exact content point under [centroid] at [centroid + panDelta] across scale changes,
     * ensuring zooming in and shrinking down both anchor strictly to the two fingers.
     */
    fun onGesture(
        zoomChange: Float,
        panDelta: Offset,
        centroid: Offset,
        viewportSize: Size
    ) {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f) return

        val currentScale = scale
        val targetScale = (currentScale * zoomChange).coerceIn(minScale, maxScale)

        // Anchored offset formula:
        // Maintains the content point under centroid stationary across scale changes
        val nextOffset = if (currentScale > 0f && targetScale > 0f) {
            (contentOffset + centroid / currentScale) - (centroid / targetScale + panDelta / currentScale)
        } else {
            contentOffset
        }

        scale = targetScale
        contentOffset = clampOffset(nextOffset, targetScale, viewportSize)
    }

    /**
     * Clamps content offset within viewport boundaries:
     * - scale >= 1.0f: offset clamped in [0, (w * s - w) / s]
     * - scale < 1.0f: offset clamped in [(w * s - w) / s, 0] so content stays within view while anchored to fingers
     */
    fun clampOffset(
        offset: Offset,
        targetScale: Float,
        viewportSize: Size
    ): Offset {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w <= 0f || h <= 0f) return Offset.Zero

        val boundX = (w * targetScale - w) / targetScale
        val minX = kotlin.math.min(0f, boundX)
        val maxX = kotlin.math.max(0f, boundX)

        val boundY = (h * targetScale - h) / targetScale
        val minY = kotlin.math.min(0f, boundY)
        val maxY = kotlin.math.max(0f, boundY)

        return Offset(
            x = offset.x.coerceIn(minX, maxX),
            y = offset.y.coerceIn(minY, maxY)
        )
    }

    /**
     * Resets scale and offset back to 1.0x resting center immediately.
     */
    fun reset() {
        scale = 1.0f
        contentOffset = Offset.Zero
    }

    /**
     * Smoothly animates scale and offset back to 1.0x resting center,
     * eliminating abrupt jarring snaps.
     */
    suspend fun animateReset() {
        val startScale = scale
        val startOffset = contentOffset
        if (startScale == 1.0f && startOffset == Offset.Zero) return

        val anim = Animatable(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        ) {
            val progress = value
            scale = startScale + (1.0f - startScale) * progress
            contentOffset = Offset(
                x = startOffset.x + (0f - startOffset.x) * progress,
                y = startOffset.y + (0f - startOffset.y) * progress
            )
        }
        scale = 1.0f
        contentOffset = Offset.Zero
    }

    /**
     * GraphicsLayer translation in screen pixel coordinates.
     * translation = -contentOffset * scale with TransformOrigin(0f, 0f)
     */
    val translationX: Float
        get() = -contentOffset.x * scale

    val translationY: Float
        get() = -contentOffset.y * scale

    val transformOrigin: TransformOrigin = TransformOrigin(0f, 0f)
}

@Composable
fun rememberPlayerZoomState(
    initialScale: Float = 1.0f,
    minScale: Float = 0.5f,
    maxScale: Float = 5.0f
): PlayerZoomState {
    return remember {
        PlayerZoomState(
            initialScale = initialScale,
            minScale = minScale,
            maxScale = maxScale
        )
    }
}
