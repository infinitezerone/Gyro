package dev.gyro.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the PiP timing invariant and the placement-event contract of [PlayerViewportState]. */
class PlayerViewportStateTest {

    @Test
    fun onContainerSizedInPipDoesNotTouchFullscreenSize() {
        val state = PlayerViewportState()
        state.onContainerSized(IntSize(100, 200), inPipLayout = false)
        assertEquals(IntSize(100, 200), state.fullscreenContainerSize)

        // Entering PiP: the container shrinks, but the PiP source-rect hint must keep
        // describing the fullscreen window - re-deriving it here re-triggers the resize
        // flash on enter.
        state.onContainerSized(IntSize(40, 40), inPipLayout = true)
        assertEquals(IntSize(100, 200), state.fullscreenContainerSize)
        assertEquals(IntSize(40, 40), state.containerSize)
    }

    @Test
    fun onVideoContainerPlacedReportsSizeChangeOnly() {
        val state = PlayerViewportState()
        assertTrue(state.onVideoContainerPlaced(Offset(1f, 2f), IntSize(50, 50)))
        // Origin-only moves must not fire the CONTAINER_LAYOUT_CHANGED trace.
        assertFalse(state.onVideoContainerPlaced(Offset(3f, 2f), IntSize(50, 50)))
        assertFalse(state.onVideoContainerPlaced(Offset(3f, 2f), IntSize(50, 50)))
        assertTrue(state.onVideoContainerPlaced(Offset(3f, 2f), IntSize(60, 50)))
    }

    @Test
    fun onVideoContainerPlacedUpdatesBoundsInWindow() {
        val state = PlayerViewportState()
        val rect = android.graphics.Rect().apply {
            left = 10
            top = 20
            right = 110
            bottom = 120
        }
        state.onVideoContainerPlaced(Offset(10f, 20f), IntSize(100, 100), rect)
        assertEquals(10, state.videoBoundsInWindow?.left)
        assertEquals(20, state.videoBoundsInWindow?.top)
        assertEquals(110, state.videoBoundsInWindow?.right)
        assertEquals(120, state.videoBoundsInWindow?.bottom)
    }

    @Test
    fun appliedBufferSizeIsIndependentOfContainerSize() {
        val state = PlayerViewportState()
        state.onVideoContainerPlaced(Offset.Zero, IntSize(100, 100))
        state.onAppliedBufferSizeChanged(IntSize(2261, 1272))
        assertEquals(IntSize(2261, 1272), state.appliedBufferSize)
        // The container report must not clobber the buffer report.
        state.onVideoContainerPlaced(Offset.Zero, IntSize(101, 100))
        assertEquals(IntSize(2261, 1272), state.appliedBufferSize)
    }
}
