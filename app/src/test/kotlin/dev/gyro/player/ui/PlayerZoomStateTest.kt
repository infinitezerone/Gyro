package dev.gyro.player.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerZoomStateTest {

    @Test
    fun testInitialState() {
        val zoomState = PlayerZoomState()
        assertEquals(1.0f, zoomState.scale, 0.001f)
        assertEquals(Offset.Zero, zoomState.contentOffset)
        assertEquals(0f, zoomState.translationX, 0.001f)
        assertEquals(0f, zoomState.translationY, 0.001f)
        assertEquals(TransformOrigin(0f, 0f), zoomState.transformOrigin)
        assertFalse(zoomState.isZoomed)
    }

    @Test
    fun testCenterPinchToZoom() {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)
        val centerCentroid = Offset(500f, 300f)

        // Zoom 2x around screen center
        zoomState.onGesture(
            zoomChange = 2.0f,
            panDelta = Offset.Zero,
            centroid = centerCentroid,
            viewportSize = viewport,
            isPinch = true
        )

        assertEquals(2.0f, zoomState.scale, 0.001f)
        assertTrue(zoomState.isZoomed)
        assertEquals(TransformOrigin(0f, 0f), zoomState.transformOrigin)

        val renderedX = (500f - zoomState.contentOffset.x) * zoomState.scale
        val renderedY = (300f - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(500f, renderedX, 0.01f)
        assertEquals(300f, renderedY, 0.01f)
    }

    @Test
    fun testOffCenterAnchoredZoomWithoutSlippage() {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)
        // Anchor near top-left: (200, 150)
        val anchor = Offset(200f, 150f)

        // Zoom 2.5x around anchor
        zoomState.onGesture(
            zoomChange = 2.5f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )

        assertEquals(2.5f, zoomState.scale, 0.001f)

        val renderedX = (200f - zoomState.contentOffset.x) * zoomState.scale
        val renderedY = (150f - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(200f, renderedX, 0.01f)
        assertEquals(150f, renderedY, 0.01f)
    }

    @Test
    fun testContinuousShrinkAcrossOnePointZeroMaintainsAnchor() {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)
        val anchor = Offset(700f, 400f)

        // 1. Zoom in to 2.0x around anchor
        zoomState.onGesture(
            zoomChange = 2.0f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )
        assertEquals(2.0f, zoomState.scale, 0.001f)
        var renderedX = (anchor.x - zoomState.contentOffset.x) * zoomState.scale
        var renderedY = (anchor.y - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(anchor.x, renderedX, 0.01f)
        assertEquals(anchor.y, renderedY, 0.01f)

        // 2. Continuously shrink down to 1.5x
        zoomState.onGesture(
            zoomChange = 1.5f / 2.0f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )
        assertEquals(1.5f, zoomState.scale, 0.001f)
        renderedX = (anchor.x - zoomState.contentOffset.x) * zoomState.scale
        renderedY = (anchor.y - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(anchor.x, renderedX, 0.01f)
        assertEquals(anchor.y, renderedY, 0.01f)

        // 3. Continuously shrink down to 1.0f (must NOT snap to origin!)
        zoomState.onGesture(
            zoomChange = 1.0f / 1.5f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )
        assertEquals(1.0f, zoomState.scale, 0.001f)
        renderedX = (anchor.x - zoomState.contentOffset.x) * zoomState.scale
        renderedY = (anchor.y - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(anchor.x, renderedX, 0.01f)
        assertEquals(anchor.y, renderedY, 0.01f)

        // 4. Continuously shrink below 1.0f to 0.8f
        zoomState.onGesture(
            zoomChange = 0.8f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )
        assertEquals(0.8f, zoomState.scale, 0.001f)
        renderedX = (anchor.x - zoomState.contentOffset.x) * zoomState.scale
        renderedY = (anchor.y - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(anchor.x, renderedX, 0.01f)
        assertEquals(anchor.y, renderedY, 0.01f)
    }

    @Test
    fun testShrinkBelowOneAnchoredAroundFingers() {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)
        val anchor = Offset(400f, 250f)

        // Shrink to 0.8x anchored around (400, 250)
        zoomState.onGesture(
            zoomChange = 0.8f,
            panDelta = Offset.Zero,
            centroid = anchor,
            viewportSize = viewport,
            isPinch = true
        )

        assertEquals(0.8f, zoomState.scale, 0.001f)
        assertFalse(zoomState.isZoomed)

        // The point under the fingers must remain exactly at (400, 250)
        val renderedX = (400f - zoomState.contentOffset.x) * zoomState.scale
        val renderedY = (250f - zoomState.contentOffset.y) * zoomState.scale
        assertEquals(400f, renderedX, 0.01f)
        assertEquals(250f, renderedY, 0.01f)
    }

    @Test
    fun testResetZoom() {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)
        zoomState.onGesture(3.0f, Offset(50f, 50f), Offset(400f, 300f), viewport, isPinch = true)
        assertTrue(zoomState.isZoomed)

        zoomState.reset()
        assertEquals(1.0f, zoomState.scale, 0.001f)
        assertEquals(Offset.Zero, zoomState.contentOffset)
        assertEquals(0f, zoomState.translationX, 0.001f)
        assertEquals(0f, zoomState.translationY, 0.001f)
        assertFalse(zoomState.isZoomed)
    }

    @Test
    fun testScaleClampingWithinMinMax() {
        val zoomState = PlayerZoomState(minScale = 0.5f, maxScale = 5.0f)
        val viewport = Size(1000f, 600f)

        // Try zooming beyond max (10x)
        zoomState.onGesture(10.0f, Offset.Zero, Offset(500f, 300f), viewport, isPinch = true)
        assertEquals(5.0f, zoomState.scale, 0.001f)

        // Try zooming below min (0.1x)
        zoomState.onGesture(0.01f, Offset.Zero, Offset(500f, 300f), viewport, isPinch = true)
        assertEquals(0.5f, zoomState.scale, 0.001f)
    }

    @Test
    fun testAnimateSettleRestoresOutOfBoundsOffset() = runBlocking {
        val zoomState = PlayerZoomState()
        val viewport = Size(1000f, 600f)

        // Zoom 2x with off-center pinch leaving offset out of bounds
        zoomState.onGesture(
            zoomChange = 2.0f,
            panDelta = Offset(-300f, -300f),
            centroid = Offset(100f, 100f),
            viewportSize = viewport,
            isPinch = true
        )
        assertEquals(2.0f, zoomState.scale, 0.001f)

        // Animate settle back to bounds
        zoomState.animateSettle(viewport)
        val maxX = (1000f * 2.0f - 1000f) / 2.0f
        val maxY = (600f * 2.0f - 600f) / 2.0f
        assertTrue(zoomState.contentOffset.x in 0f..maxX)
        assertTrue(zoomState.contentOffset.y in 0f..maxY)
    }

    @Test
    fun testAnimateResetSynchronizesScaleAndTranslation() = runBlocking {
        val clock = object : androidx.compose.runtime.MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                return onFrame(System.nanoTime())
            }
        }
        kotlinx.coroutines.withContext(clock) {
            val zoomState = PlayerZoomState()
            val viewport = Size(1000f, 600f)
            val anchor = Offset(700f, 400f)

            // Shrink to 0.7x anchored around (700, 400)
            zoomState.onGesture(
                zoomChange = 0.7f,
                panDelta = Offset.Zero,
                centroid = anchor,
                viewportSize = viewport,
                isPinch = true
            )
            assertEquals(0.7f, zoomState.scale, 0.001f)
            assertTrue(zoomState.translationX != 0f)

            // Reset animation should complete with scale = 1.0f and translation = 0f
            zoomState.animateReset()
            assertEquals(1.0f, zoomState.scale, 0.001f)
            assertEquals(0f, zoomState.translationX, 0.001f)
            assertEquals(0f, zoomState.translationY, 0.001f)
            assertEquals(Offset.Zero, zoomState.contentOffset)
        }
    }

    @Test
    fun testVerticalAndHorizontalClampingWithContentBounds() {
        val zoomState = PlayerZoomState()
        // Portrait viewport: 1000 x 2000, 16:9 video is 1000 x 562, yOffset = (2000 - 562) / 2 = 719
        val viewport = Size(1000f, 2000f)
        val videoRect = androidx.compose.ui.geometry.Rect(0f, 719f, 1000f, 1281f)

        // 1. Zoom 1.5x in portrait: scaled height is 562 * 1.5 = 843 <= 2000 (shorter than viewport)
        // Scaled width is 1000 * 1.5 = 1500 > 1000 (wider than viewport)
        zoomState.onGesture(
            zoomChange = 1.5f,
            panDelta = Offset(500f, 500f), // User tries to pan wildly
            centroid = Offset(500f, 1000f),
            viewportSize = viewport,
            contentBounds = videoRect,
            isPinch = false
        )

        // Vertical MUST be locked centered, not swimming freely
        val expectedY = 719f - (2000f / 1.5f - 562f) / 2f
        assertEquals(expectedY, zoomState.contentOffset.y, 0.01f)

        // Horizontal MUST be clamped strictly between 0 and (1000 - 1000 / 1.5)
        val maxOffsetW = 1000f - 1000f / 1.5f
        assertTrue(zoomState.contentOffset.x in 0f..maxOffsetW)

        // 2. Zoom 4.0x: scaled height is 562 * 4.0 = 2248 > 2000 (taller than viewport)
        // Now vertical panning is permitted, but strictly bounded between top and bottom video edges
        zoomState.onGesture(
            zoomChange = 4.0f / 1.5f,
            panDelta = Offset(0f, 9999f), // User tries to pan completely offscreen
            centroid = Offset(500f, 1000f),
            viewportSize = viewport,
            contentBounds = videoRect,
            isPinch = false
        )
        val minY = 719f
        val maxY = 1281f - 2000f / 4.0f
        assertEquals(minY, zoomState.contentOffset.y, 0.01f) // Clamped to top edge
    }
}
