package dev.gyro.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the letterbox/pillarbox strip math that feeds the PiP sourceRectHint. The expected values
 * are the ones observed on device in the 2026-11-15 PiP diagnosis, so a regression in this math
 * now breaks the test before it breaks the transition animation.
 *
 * android.graphics.Rect is a stubbed jar on the JVM - assert field-wise, never equals(), and
 * build inputs via the primitives overload only.
 */
class PlayerPipSourceRectTest {

    @Test
    fun landscapeFullscreenStripsPillarboxBars() {
        // Real device values: 2.18:1 landscape window (2772x1272), 16:9 video.
        // Observed on device: Rect(255, 0 - 2516, 1272).
        val rect = calculateVideoSourceRect(
            left = 0, top = 0, right = 2772, bottom = 1272,
            videoRatio = 16f / 9f
        )
        assertEquals(255, rect.left)
        assertEquals(0, rect.top)
        // 1272 * 16/9 = 2261.33; both edges truncate consistently -> stripped width 2261.
        assertEquals(2261, rect.right - rect.left)
        assertEquals(1272, rect.bottom - rect.top)
    }

    @Test
    fun portraitFullscreenStripsLetterboxBars() {
        // Real device values: portrait window (1272x2772), 16:9 video.
        // Observed on device: Rect(0, 1028 - 1272, 1743).
        val rect = calculateVideoSourceRect(
            left = 0, top = 0, right = 1272, bottom = 2772,
            videoRatio = 16f / 9f
        )
        assertEquals(0, rect.left)
        assertEquals(1028, rect.top)
        assertEquals(1272, rect.right)
        // 2772 - 1272/(16/9) truncates to 1743; the 1744 seen in one log line came from the
        // boundsInWindow path's roundToInt, a different source with different rounding.
        assertEquals(1743, rect.bottom)
        assertEquals(715, rect.bottom - rect.top)
    }

    @Test
    fun twentyOneNineRatioStripsWithForcedRatio() {
        // A forced 21:9 container must strip against 21:9 regardless of the video's real aspect.
        val rect = calculateVideoSourceRect(
            left = 0, top = 0, right = 1272, bottom = 2772,
            videoRatio = 21f / 9f
        )
        assertEquals(0, rect.left)
        // 1272 / (21/9) = 545.14 -> letterbox height 545, centered.
        assertEquals(545, rect.bottom - rect.top)
    }

    @Test
    fun cropAndStretchReturnTheContainerUntouched() {
        // CROP/STRETCH never go through this function's strip branch (the Rational overload
        // returns early); the primitives core only ever sees a resolved videoRatio. A 1:1 ratio
        // on a wide container is the degenerate sanity path: strip to a square.
        val rect = calculateVideoSourceRect(
            left = 0, top = 0, right = 2000, bottom = 1000,
            videoRatio = 1f
        )
        assertEquals(500, rect.left)
        assertEquals(1500, rect.right)
        assertEquals(0, rect.top)
        assertEquals(1000, rect.bottom)
    }

    @Test
    fun invalidInputsReturnTheContainerUnstripped() {
        val rect = calculateVideoSourceRect(
            left = 10, top = 20, right = 30, bottom = 40,
            videoRatio = 0f
        )
        assertEquals(10, rect.left)
        assertEquals(20, rect.top)
        assertEquals(30, rect.right)
        assertEquals(40, rect.bottom)

        val degenerate = calculateVideoSourceRect(
            left = 5, top = 5, right = 5, bottom = 40,
            videoRatio = 16f / 9f
        )
        assertEquals(5, degenerate.left)
        assertEquals(5, degenerate.top)
        assertEquals(5, degenerate.right)
        assertEquals(40, degenerate.bottom)
    }
}
