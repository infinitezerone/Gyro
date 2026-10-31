package dev.gyro.player.ui

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
 * precisely aligned with 《ComposeStyleAPI Zoom 系统设计与关键算法》 and ReadScreen.kt.
 *
 * Separates Screen/Viewport coordinates from Content coordinates:
 * - Content Point: screenPoint / scale + offset
 * - Screen Point: (contentPoint - offset) * scale
 * - graphicsLayer translation: -offset * scale
 * - graphicsLayer transformOrigin: Center when scale < 1.0f else (0, 0)
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
     * Maintains the exact content point under [centroid] at [centroid + panDelta] across scale changes.
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
        // nextOffset = (currentOffset + centroid / currentScale) - (centroid / targetScale + panDelta / currentScale)
        val nextOffset = if (currentScale > 0f && targetScale > 0f) {
            (contentOffset + centroid / currentScale) - (centroid / targetScale + panDelta / currentScale)
        } else {
            contentOffset
        }

        scale = targetScale
        contentOffset = clampOffset(nextOffset, targetScale, viewportSize)
    }

    /**
     * Clamps content offset within content boundaries:
     * - Content >= Viewport (targetScale >= 1.0f): [0, (w * s - w) / s]
     * - Content < Viewport (targetScale < 1.0f): resting centered offset
     */
    fun clampOffset(
        offset: Offset,
        targetScale: Float,
        viewportSize: Size
    ): Offset {
        val w = viewportSize.width
        val h = viewportSize.height
        if (w <= 0f || h <= 0f) return Offset.Zero

        return if (targetScale >= 1.0f) {
            val maxX = ((w * targetScale - w) / targetScale).coerceAtLeast(0f)
            val maxY = ((h * targetScale - h) / targetScale).coerceAtLeast(0f)
            Offset(
                x = offset.x.coerceIn(0f, maxX),
                y = offset.y.coerceIn(0f, maxY)
            )
        } else {
            // Centered offset when smaller than viewport
            Offset(
                x = ((w / 2f) * (targetScale - 1f)) / targetScale,
                y = ((h / 2f) * (targetScale - 1f)) / targetScale
            )
        }
    }

    /**
     * Resets scale and offset back to 1.0x resting center.
     */
    fun reset() {
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

    /**
     * TransformOrigin is Center for scale < 1f, or (0,0) for scale >= 1f
     */
    val transformOrigin: TransformOrigin
        get() = if (scale < 1.0f) TransformOrigin.Center else TransformOrigin(0f, 0f)
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
