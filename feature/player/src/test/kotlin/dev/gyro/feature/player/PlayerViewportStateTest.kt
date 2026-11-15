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
    fun onContainerSizedWithFrozenFullscreenDoesNotTouchFullscreenSize() {
        val state = PlayerViewportState()
        state.onContainerSized(IntSize(100, 200), freezeFullscreen = false)
        assertEquals(IntSize(100, 200), state.fullscreenContainerSize)

        // Entering PiP: the container shrinks, but the PiP source-rect hint must keep
        // describing the fullscreen window - re-deriving it here re-triggers the resize
        // flash on enter.
        state.onContainerSized(IntSize(40, 40), freezeFullscreen = true)
        assertEquals(IntSize(100, 200), state.fullscreenContainerSize)
        assertEquals(IntSize(40, 40), state.containerSize)
    }

    @Test
    fun fullscreenSizeSurvivesTheWindowShrinkThatArrivesBeforeTheModeCallback() {
        // The regression from the 2026-11-15 PiP repro: the physical window shrinks BEFORE
        // the system's picture-in-picture mode callback flips isPipLayout, so the shrink
        // event arrives with the freeze flag not yet raised if callers gate on the layout
        // flag alone. The caller must therefore raise the freeze at the enter request
        // (shouldHideChrome includes pipRequested); this test pins the holder side.
        val state = PlayerViewportState()
        state.onContainerSized(IntSize(2772, 1272), freezeFullscreen = false)
        assertEquals(IntSize(2772, 1272), state.fullscreenContainerSize)

        // Enter requested -> freeze raised BEFORE the shrink event arrives.
        state.onContainerSized(IntSize(1019, 573), freezeFullscreen = true)
        assertEquals(IntSize(2772, 1272), state.fullscreenContainerSize)
        assertEquals(IntSize(1019, 573), state.containerSize)

        // PiP window adjusts again (1019 -> 1160): still frozen, still no poisoning.
        state.onContainerSized(IntSize(1160, 653), freezeFullscreen = true)
        assertEquals(IntSize(2772, 1272), state.fullscreenContainerSize)

        // Exit settles: freeze released, the real fullscreen size lands again.
        state.onContainerSized(IntSize(2772, 1272), freezeFullscreen = false)
        assertEquals(IntSize(2772, 1272), state.fullscreenContainerSize)
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
    fun nullBoundsKeepsTheLastFullscreenRect() {
        // During the PiP in-flight freeze the placement callback passes null bounds: the holder
        // must keep the last fullscreen rect, otherwise the PiP window rect becomes the
        // source-rect hint and PIP params re-fire mid-animation.
        // android.graphics.Rect is a stubbed jar in JVM tests - assert field-wise, never equals.
        val state = PlayerViewportState()
        val fullscreen = android.graphics.Rect().apply {
            left = 256
            top = 0
            right = 2517
            bottom = 1272
        }
        state.onVideoContainerPlaced(Offset(256f, 0f), IntSize(2261, 1272), fullscreen)

        state.onVideoContainerPlaced(Offset.Zero, IntSize(696, 392), null)
        assertEquals(256, state.videoBoundsInWindow?.left)
        assertEquals(0, state.videoBoundsInWindow?.top)
        assertEquals(2517, state.videoBoundsInWindow?.right)
        assertEquals(1272, state.videoBoundsInWindow?.bottom)
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
