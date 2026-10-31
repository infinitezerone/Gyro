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
 * strictly maintaining the touch centroid as the anchor across all scales (both > 1f, = 1f, and < 1f).
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
     * ensuring zooming in and shrinking down both anchor strictly to the two fingers without sudden snapping.
     *
     * @param isPinch true for multi-touch pinch gestures where the content must remain anchored
     *                to the fingers without boundary clamping snapping the view to origin at 1.0f.
     */
    fun onGesture(
        zoomChange: Float,
        panDelta: Offset,
        centroid: Offset,
        viewportSize: Size,
        isPinch: Boolean = false
    ) {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f) return

        val currentScale = scale
        val targetScale = (currentScale * zoomChange).coerceIn(minScale, maxScale)

        // Anchored offset formula:
        // screenPoint = (contentPoint - offset) * scale
        // anchoredContentPoint = contentOffset + centroid / currentScale
        // target screen position = centroid + panDelta
        // nextOffset = anchoredContentPoint - (centroid + panDelta) / targetScale
        val nextOffset = if (currentScale > 0f && targetScale > 0f) {
            (contentOffset + centroid / currentScale) - (centroid + panDelta) / targetScale
        } else {
            contentOffset
        }

        scale = targetScale
        contentOffset = if (isPinch) {
            // Keep anchor locked to fingers during pinch gesture (even across 1.0f or below 1.0f)
            nextOffset
        } else {
            // Single-finger drag clamped within content boundary
            clampOffset(nextOffset, targetScale, viewportSize)
        }
    }

    /**
     * Clamps content offset within viewport boundaries:
     * - scale >= 1.0f: offset clamped in [0, (w * s - w) / s]
     * - scale < 1.0f: resting center offset ((w - w / s) / 2, (h - h / s) / 2)
     */
    fun clampOffset(
        offset: Offset,
        targetScale: Float,
        viewportSize: Size
    ): Offset {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w <= 0f || h <= 0f) return Offset.Zero

        if (targetScale < 1.0f) {
            return Offset(
                x = (w - w / targetScale) / 2f,
                y = (h - h / targetScale) / 2f
            )
        }

        val maxX = (w * targetScale - w) / targetScale
        val maxY = (h * targetScale - h) / targetScale

        return Offset(
            x = offset.x.coerceIn(0f, maxX),
            y = offset.y.coerceIn(0f, maxY)
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
     * Smoothly springs contentOffset back into valid resting boundaries,
     * used when multi-touch release occurs at scale >= 1.0f.
     */
    suspend fun animateSettle(viewportSize: Size) {
        val targetOffset = clampOffset(contentOffset, scale, viewportSize)
        if (targetOffset == contentOffset) return

        val startOffset = contentOffset
        val anim = Animatable(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
        ) {
            val progress = value
            contentOffset = Offset(
                x = startOffset.x + (targetOffset.x - startOffset.x) * progress,
                y = startOffset.y + (targetOffset.y - startOffset.y) * progress
            )
        }
        contentOffset = targetOffset
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
