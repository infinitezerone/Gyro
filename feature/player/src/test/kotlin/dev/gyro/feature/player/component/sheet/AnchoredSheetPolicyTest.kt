package dev.gyro.feature.player.component.sheet

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class AnchoredSheetPolicyTest {

    private val distance = 1000f
    private val velocityThreshold = 900f

    @Test
    fun resolveSheetTarget_deadStopUsesPositionalThreshold() {
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 349f,
                dismissDistancePx = distance,
                velocityPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Hidden,
            resolveSheetTarget(
                offsetPx = 350f,
                dismissDistancePx = distance,
                velocityPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Hidden,
            resolveSheetTarget(
                offsetPx = 600f,
                dismissDistancePx = distance,
                velocityPxPerSec = 0f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
    }

    @Test
    fun resolveSheetTarget_fastDismissFlickOverridesSmallOffset() {
        assertEquals(
            SheetAnchor.Hidden,
            resolveSheetTarget(
                offsetPx = 50f,
                dismissDistancePx = distance,
                velocityPxPerSec = velocityThreshold,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Hidden,
            resolveSheetTarget(
                offsetPx = 0f,
                dismissDistancePx = distance,
                velocityPxPerSec = 1500f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
    }

    @Test
    fun resolveSheetTarget_fastReopenFlickOverridesLargeOffset() {
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 650f,
                dismissDistancePx = distance,
                velocityPxPerSec = -velocityThreshold,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 800f,
                dismissDistancePx = distance,
                velocityPxPerSec = -1600f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
    }

    @Test
    fun resolveSheetTarget_slowReleaseFallsBackToPositionalThreshold() {
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 200f,
                dismissDistancePx = distance,
                velocityPxPerSec = 400f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Hidden,
            resolveSheetTarget(
                offsetPx = 450f,
                dismissDistancePx = distance,
                velocityPxPerSec = -400f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
    }

    @Test
    fun resolveSheetTarget_degenerateInputsStayOpen() {
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = Float.NaN,
                dismissDistancePx = distance,
                velocityPxPerSec = 2000f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 500f,
                dismissDistancePx = 0f,
                velocityPxPerSec = 2000f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
        assertEquals(
            SheetAnchor.Open,
            resolveSheetTarget(
                offsetPx = 500f,
                dismissDistancePx = -100f,
                velocityPxPerSec = 2000f,
                velocityThresholdPxPerSec = velocityThreshold,
            ),
        )
    }

    @Test
    fun computeSheetOpenProgress_tracksAndClampsAcrossBounds() {
        val delta = 1e-4f
        assertEquals(0f, computeSheetOpenProgress(Float.NaN, distance), delta)
        assertEquals(0f, computeSheetOpenProgress(100f, 0f), delta)
        assertEquals(1f, computeSheetOpenProgress(0f, distance), delta)
        assertEquals(0.6f, computeSheetOpenProgress(400f, distance), delta)
        assertEquals(0f, computeSheetOpenProgress(distance, distance), delta)
        assertEquals(1f, computeSheetOpenProgress(-50f, distance), delta)
        assertEquals(0f, computeSheetOpenProgress(1200f, distance), delta)
    }

    @Test
    fun computeSheetVisibleHeightPx_tracksPortraitAndZeroesLandscape() {
        val delta = 1e-4f
        assertEquals(0f, computeSheetVisibleHeightPx(0f, distance, isLandscape = true), delta)
        assertEquals(0f, computeSheetVisibleHeightPx(300f, distance, isLandscape = true), delta)
        assertEquals(0f, computeSheetVisibleHeightPx(Float.NaN, distance, isLandscape = false), delta)
        assertEquals(0f, computeSheetVisibleHeightPx(0f, 0f, isLandscape = false), delta)

        assertEquals(1000f, computeSheetVisibleHeightPx(0f, distance, isLandscape = false), delta)
        assertEquals(650f, computeSheetVisibleHeightPx(350f, distance, isLandscape = false), delta)
        assertEquals(0f, computeSheetVisibleHeightPx(distance, distance, isLandscape = false), delta)
    }

    @Test
    fun updateBounds_beforeOpen_keepsSheetAtHiddenAcrossMultipleLayoutPasses() {
        val state = AnchoredSheetState(density = Density(3f), initialIsLandscape = false)
        state.updateBounds(distancePx = 900f, landscape = false)
        assertEquals(900f, state.anchoredState.offset, 1e-4f)
        assertEquals(0f, state.openProgress, 1e-4f)

        // Second measure pass before open() runs (e.g. portrait inset change) must stay at Hidden.
        state.updateBounds(distancePx = 960f, landscape = false)
        assertEquals(960f, state.anchoredState.offset, 1e-4f)
        assertEquals(0f, state.openProgress, 1e-4f)
    }

    @Test
    fun updateBounds_whileClosing_freezesAnchorsAndDoesNotSnapOpenSheet() {
        val state = AnchoredSheetState(density = Density(3f), initialIsLandscape = false)
        state.updateBounds(distancePx = 900f, landscape = false)
        // Simulate sheet dragged/opened to Open (0f)
        state.dispatchNestedDelta(-900f)
        assertEquals(0f, state.anchoredState.offset, 1e-4f)
        assertEquals(1f, state.openProgress, 1e-4f)

        // Dismiss triggered in portrait: system bars re-showing changes measured height during close.
        state.setClosingTarget(true)
        state.updateBounds(distancePx = 1020f, landscape = false)

        // Offset and dismissDistancePx must remain untouched so close() can animate from 0f -> 900f.
        assertEquals(0f, state.anchoredState.offset, 1e-4f)
        assertEquals(900f, state.dismissDistancePx, 1e-4f)
        assertEquals(1f, state.openProgress, 1e-4f)
    }

    @Test
    fun computeScaledSheetDurationMs_scalesProportionallyAndClampsToFloor() {
        assertEquals(
            SHEET_EXIT_DURATION_MS,
            computeScaledSheetDurationMs(
                travelPx = 1000f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_EXIT_DURATION_MS,
                minDurationMs = 80,
            ),
        )
        assertEquals(
            SHEET_ENTER_DURATION_MS,
            computeScaledSheetDurationMs(
                travelPx = 1000f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_ENTER_DURATION_MS,
                minDurationMs = 120,
            ),
        )
        assertEquals(
            100,
            computeScaledSheetDurationMs(
                travelPx = 500f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_EXIT_DURATION_MS,
                minDurationMs = 80,
            ),
        )
        assertEquals(
            200,
            computeScaledSheetDurationMs(
                travelPx = -500f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_ENTER_DURATION_MS,
                minDurationMs = 120,
            ),
        )
        assertEquals(
            80,
            computeScaledSheetDurationMs(
                travelPx = 50f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_EXIT_DURATION_MS,
                minDurationMs = 80,
            ),
        )
        assertEquals(
            120,
            computeScaledSheetDurationMs(
                travelPx = 50f,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_ENTER_DURATION_MS,
                minDurationMs = 120,
            ),
        )
        assertEquals(
            SHEET_EXIT_DURATION_MS,
            computeScaledSheetDurationMs(
                travelPx = 200f,
                dismissDistancePx = 0f,
                fullDurationMs = SHEET_EXIT_DURATION_MS,
                minDurationMs = 80,
            ),
        )
        assertEquals(
            SHEET_ENTER_DURATION_MS,
            computeScaledSheetDurationMs(
                travelPx = Float.NaN,
                dismissDistancePx = 1000f,
                fullDurationMs = SHEET_ENTER_DURATION_MS,
                minDurationMs = 120,
            ),
        )
    }
}
