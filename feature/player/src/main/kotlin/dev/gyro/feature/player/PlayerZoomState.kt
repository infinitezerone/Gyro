package dev.gyro.feature.player

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
import androidx.compose.ui.geometry.Rect
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
     * @param contentBounds Physical bounding rect of actual video content within viewport (stripping black bars).
     * @param isPinch true for multi-touch pinch gestures where the content must remain anchored
     *                to the fingers without boundary clamping snapping the view to origin at 1.0f.
     */
    fun onGesture(
        zoomChange: Float,
        panDelta: Offset,
        centroid: Offset,
        viewportSize: Size,
        contentBounds: Rect? = null,
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
            // Single-finger drag clamped within actual content boundaries (both X and Y)
            clampOffset(nextOffset, targetScale, viewportSize, contentBounds)
        }
    }

    /**
     * Clamps content offset within physical content boundaries in both horizontal (X) and vertical (Y) axes:
     * - When scaled content is wider/taller than viewport: clamped strictly so video edges never expose black void.
     * - When scaled content is narrower/shorter than viewport: locked centered in that dimension.
     */
    fun clampOffset(
        offset: Offset,
        targetScale: Float,
        viewportSize: Size,
        contentBounds: Rect? = null
    ): Offset {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w <= 0f || h <= 0f) return Offset.Zero

        val videoW = contentBounds?.width?.takeIf { it > 0f } ?: w
        val videoH = contentBounds?.height?.takeIf { it > 0f } ?: h
        val xOffset = contentBounds?.left ?: 0f
        val yOffset = contentBounds?.top ?: 0f

        val scaledW = videoW * targetScale
        val scaledH = videoH * targetScale

        // Horizontal Clamping (X)
        val clampedX = if (scaledW <= w) {
            // Content narrower than viewport: center horizontally
            xOffset - (w / targetScale - videoW) / 2f
        } else {
            // Content wider than viewport: clamp between left and right video edges
            val minX = xOffset
            val maxX = (xOffset + videoW) - w / targetScale
            offset.x.coerceIn(minX, maxX)
        }

        // Vertical Clamping (Y)
        val clampedY = if (scaledH <= h) {
            // Content shorter than viewport: center vertically
            yOffset - (h / targetScale - videoH) / 2f
        } else {
            // Content taller than viewport: clamp between top and bottom video edges
            val minY = yOffset
            val maxY = (yOffset + videoH) - h / targetScale
            offset.y.coerceIn(minY, maxY)
        }

        return Offset(clampedX, clampedY)
    }

    /**
     * Resets scale and offset back to 1.0x resting center immediately.
     */
    fun reset(
        viewportSize: Size? = null,
        contentBounds: Rect? = null
    ) {
        scale = 1.0f
        contentOffset = if (viewportSize != null && viewportSize.width > 0f && viewportSize.height > 0f) {
            clampOffset(Offset.Zero, 1.0f, viewportSize, contentBounds)
        } else {
            Offset.Zero
        }
    }

    /**
     * Smoothly animates scale and screen translation back to 1.0x resting center,
     * maintaining 1:1 visual synchronization between scale expansion and translation movement on screen.
     */
    suspend fun animateReset(
        viewportSize: Size? = null,
        contentBounds: Rect? = null
    ) {
        val startScale = scale
        val startTransX = translationX
        val startTransY = translationY

        val targetOffset = if (viewportSize != null && viewportSize.width > 0f && viewportSize.height > 0f) {
            clampOffset(Offset.Zero, 1.0f, viewportSize, contentBounds)
        } else {
            Offset.Zero
        }
        val targetTransX = -targetOffset.x * 1.0f
        val targetTransY = -targetOffset.y * 1.0f

        if (startScale == 1.0f && startTransX == targetTransX && startTransY == targetTransY) return

        val anim = Animatable(0f)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        ) {
            val progress = value
            val curScale = startScale + (1.0f - startScale) * progress
            val curTransX = startTransX + (targetTransX - startTransX) * progress
            val curTransY = startTransY + (targetTransY - startTransY) * progress

            scale = curScale
            contentOffset = if (curScale > 0f) {
                Offset(-curTransX / curScale, -curTransY / curScale)
            } else {
                targetOffset
            }
        }
        scale = 1.0f
        contentOffset = targetOffset
    }

    /**
     * Smoothly springs contentOffset back into valid resting boundaries,
     * used when multi-touch release occurs at scale >= 1.0f.
     */
    suspend fun animateSettle(
        viewportSize: Size,
        contentBounds: Rect? = null
    ) {
        val targetOffset = clampOffset(contentOffset, scale, viewportSize, contentBounds)
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
