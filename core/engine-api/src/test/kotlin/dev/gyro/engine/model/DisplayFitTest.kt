package dev.gyro.engine.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge-case coverage complementing GyroPlayerEngineTest's mainline cases for the pure display
 * fit math. These run on the JVM with no Android dependencies by construction.
 */
class DisplayFitTest {

    @Test
    fun invalidInputsBypassFittingAndClampToOnePixel() {
        // Invalid dims bypass fitting entirely: returns (max(1, videoW), max(1, videoH)).
        assertEquals(Pair(1, 1), computeDisplayFittedBufferSize(0, 0, 1272, 2772))
        assertEquals(Pair(1, 1), computeDisplayFittedBufferSize(-640, -360, 1272, 0))
        assertEquals(Pair(1, 1), computeDisplayFittedBufferSize(0, 0, 0, 0))
    }

    @Test
    fun squareVideoFitsShortEdgeOnTallDisplay() {
        // Portrait display 1272x2772: shortEdge=1272, longEdge=2772; aspect(1.0) <= boxAspect(≈2.18)
        // → h = maxH = shortEdge, w = h * aspect → the square fits the display's short edge.
        assertEquals(Pair(1272, 1272), computeDisplayFittedBufferSize(512, 512, 1272, 2772))
    }

    @Test
    fun squareVideoFitsShortEdgeOnLandscapeDisplay() {
        // Landscape display 2772x1272: min/max swap yields the same shortEdge → same result.
        assertEquals(Pair(1272, 1272), computeDisplayFittedBufferSize(512, 512, 2772, 1272))
    }

    @Test
    fun fpsToleranceWindowBoundaryInsideSnapsOutsideKeeps() {
        // Nominal rates match in list order: 59.94 precedes 60.0, and |62-59.94|/59.94 = 3.44%
        // is within the 3.5% window, so 62.0 snaps to 59.94. 62.2 (3.77%) is outside → passthrough.
        assertEquals(59.94f, normalizeContentFps(62.0f), 0.001f)
        assertEquals(62.2f, normalizeContentFps(62.2f), 0.001f)
    }

    @Test
    fun fpsAboveThousandClampsToZero() {
        assertEquals(0f, normalizeContentFps(1500f), 0.001f)
    }

    @Test
    fun fpsJustAboveOnePassesThrough() {
        assertEquals(1.1f, normalizeContentFps(1.1f), 0.001f)
    }
}
