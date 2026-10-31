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
 * Benchmark-aligned anchored zoom & pan math model,
 * based on the dual-coordinate system design in 《ComposeStyleAPI Zoom 系统设计与关键算法》.
 *
 * Separates Screen/Viewport coordinates from Content coordinates:
 * - Content Point: screenPoint / scale + offset
 * - Screen Point: (contentPoint - offset) * scale
 * - graphicsLayer translation: -offset * scale with TransformOrigin(0f, 0f)
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

        // 1. Calculate anchored content offset:
        // nextOffset = (currentOffset + centroid / currentScale) - (centroid / targetScale + panDelta / targetScale)
        val nextOffset = if (currentScale > 0f && targetScale > 0f) {
            (contentOffset + centroid / currentScale) - (centroid / targetScale + panDelta / targetScale)
        } else {
            contentOffset
        }

        // 2. Clamp offset within content boundaries
        val clampedOffset = clampOffset(nextOffset, targetScale, viewportSize)

        scale = targetScale
        contentOffset = clampedOffset
    }

    /**
     * Clamps content offset within content boundaries:
     * - Content >= Viewport (targetScale >= 1.0f): [0, contentSize - viewportSize / targetScale]
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
            val maxX = (w - w / targetScale).coerceAtLeast(0f)
            val maxY = (h - h / targetScale).coerceAtLeast(0f)
            Offset(
                x = offset.x.coerceIn(0f, maxX),
                y = offset.y.coerceIn(0f, maxY)
            )
        } else {
            // Small content centering
            Offset(
                x = (w - w / targetScale) / 2f,
                y = (h - h / targetScale) / 2f
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
