package dev.gyro.feature.player.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsGestureGuardTest {

    private val density = 3.5f
    private val landscapeSize = IntSize(width = (792f * density).toInt(), height = (363f * density).toInt())
    private val portraitSize = IntSize(width = (363f * density).toInt(), height = (792f * density).toInt())

    private fun dp(value: Float): Float = value * density

    @Test
    fun controlsHidden_neverGuardsAnyPosition() {
        val metrics = computeControlsGestureGuardMetrics(
            density = density,
            statusBarTopPx = 140,
            cutoutTopPx = 140,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            navBarBottomPx = 84,
            landscapeHorizontalInsetDp = 40f,
        )

        val landscapeSpots = listOf(
            Offset(dp(200f), dp(20f)),
            Offset(dp(200f), landscapeSize.height - dp(20f)),
            Offset(dp(60f), landscapeSize.height / 2f),
            Offset(landscapeSize.width - dp(60f), landscapeSize.height / 2f),
            Offset(landscapeSize.width / 2f, landscapeSize.height / 2f),
        )
        landscapeSpots.forEach { pos ->
            assertFalse(
                "Expected unguarded when controls hidden at $pos",
                isInControlsBrightnessVolumeGuardZone(pos, landscapeSize, controlsVisible = false, metrics = metrics),
            )
        }

        val portraitSpots = listOf(
            Offset(dp(180f), dp(50f)),
            Offset(dp(180f), portraitSize.height - dp(40f)),
            Offset(dp(30f), portraitSize.height - dp(210f)),
            Offset(portraitSize.width - dp(30f), portraitSize.height - dp(210f)),
            Offset(portraitSize.width / 2f, portraitSize.height / 2f),
        )
        portraitSpots.forEach { pos ->
            assertFalse(
                "Expected unguarded when controls hidden at $pos",
                isInControlsBrightnessVolumeGuardZone(pos, portraitSize, controlsVisible = false, metrics = metrics),
            )
        }
    }

    @Test
    fun landscape_guardsTopBarBottomBarAndSideButtons_leavesOpenSidesFree() {
        val metrics = computeControlsGestureGuardMetrics(
            density = density,
            statusBarTopPx = 0,
            cutoutTopPx = 0,
            cutoutLeftPx = 140,
            cutoutRightPx = 0,
            navBarBottomPx = 0,
            landscapeHorizontalInsetDp = 40f,
        )

        // Expected guards: top = 84dp, bottom = 120dp, side width = 108dp, side halfHeight = 38dp
        assertEquals(dp(84f), metrics.landscapeTopGuardPx, 0.01f)
        assertEquals(dp(120f), metrics.landscapeBottomGuardPx, 0.01f)
        assertEquals(dp(108f), metrics.landscapeSideGuardWidthPx, 0.01f)
        assertEquals(dp(38f), metrics.landscapeSideGuardHalfHeightPx, 0.01f)

        // Top bar + 24dp buffer
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(200f), dp(83f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(200f), dp(85f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Bottom bar + scrub bar + 30dp buffer
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(350f), landscapeSize.height - dp(119f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(350f), landscapeSize.height - dp(121f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Open middle left/right (brightness & volume comfort zones)
        val centerY = landscapeSize.height / 2f
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(160f), centerY),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(landscapeSize.width - dp(160f), centerY),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Left Lock and Right Screenshot side button zones are guarded
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(70f), centerY),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(landscapeSize.width - dp(70f), centerY),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Vertically above and below side buttons within the center band remain open
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(70f), centerY - dp(50f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(landscapeSize.width - dp(70f), centerY + dp(45f)),
                landscapeSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
    }

    @Test
    fun portrait_guardsTopBarBottomBarAndLockScreenshotCorners_leavesMiddleFree() {
        val metrics = computeControlsGestureGuardMetrics(
            density = density,
            statusBarTopPx = 140,
            cutoutTopPx = 140,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            navBarBottomPx = 84,
            landscapeHorizontalInsetDp = 28f,
        )

        // Top guard: 140px (40dp) + (56 + 24)dp = 120dp
        assertEquals(dp(120f), metrics.portraitTopGuardPx, 0.01f)
        // Bottom guard: 84px (24dp) + (100 + 32)dp = 156dp
        assertEquals(dp(156f), metrics.portraitBottomGuardPx, 0.01f)

        // Top bar & buffer
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(100f), dp(119f)),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(100f), dp(122f)),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Bottom bar + scrub bar + 32dp buffer
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(180f), portraitSize.height - dp(155f)),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(180f), portraitSize.height - dp(158f)),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Bottom-left Lock & bottom-right Screenshot buttons (center around nav(24) + 100 + 84 + 24 = 232dp from bottom)
        val sideButtonY = portraitSize.height - dp(232f)
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(35f), sideButtonY),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertTrue(
            isInControlsBrightnessVolumeGuardZone(
                Offset(portraitSize.width - dp(35f), sideButtonY),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Horizontal center between Lock and Screenshot at the same Y stays open
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(portraitSize.width / 2f, sideButtonY),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )

        // Open middle left/right in portrait
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(dp(60f), portraitSize.height * 0.45f),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(portraitSize.width - dp(60f), portraitSize.height * 0.45f),
                portraitSize,
                controlsVisible = true,
                metrics = metrics,
            ),
        )
    }

    @Test
    fun edgeCases_zeroSizeNoneMetricsInvalidInputs_handledSafely() {
        val validMetrics = computeControlsGestureGuardMetrics(
            density = density,
            statusBarTopPx = 0,
            cutoutTopPx = 0,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            navBarBottomPx = 0,
            landscapeHorizontalInsetDp = 28f,
        )

        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(10f, 10f),
                IntSize.Zero,
                controlsVisible = true,
                metrics = validMetrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(10f, 10f),
                IntSize(0, 1000),
                controlsVisible = true,
                metrics = validMetrics,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset(10f, 10f),
                landscapeSize,
                controlsVisible = true,
                metrics = ControlsGestureGuardMetrics.None,
            ),
        )
        assertFalse(
            isInControlsBrightnessVolumeGuardZone(
                Offset.Unspecified,
                landscapeSize,
                controlsVisible = true,
                metrics = validMetrics,
            ),
        )

        val sanitized = computeControlsGestureGuardMetrics(
            density = Float.NaN,
            statusBarTopPx = -50,
            cutoutTopPx = -10,
            cutoutLeftPx = -5,
            cutoutRightPx = -5,
            navBarBottomPx = -20,
            landscapeHorizontalInsetDp = Float.NEGATIVE_INFINITY,
        )
        // Falls back to density = 1f and 0 for negative insets
        assertEquals(84f, sanitized.landscapeTopGuardPx, 0.01f)
        assertEquals(120f, sanitized.landscapeBottomGuardPx, 0.01f)
        assertTrue(sanitized.portraitTopGuardPx > 0f)
        assertTrue(sanitized.portraitBottomGuardPx > 0f)
    }

    @Test
    fun defaultPlayerGestureCallbacks_reflectsDynamicControlsVisibilityAndMetrics() {
        var visible = false
        var currentMetrics = ControlsGestureGuardMetrics.None

        val callbacks = DefaultPlayerGestureCallbacks(
            context = null,
            currentPosMs = { 0L },
            durationMs = { 0L },
            scrubState = null,
            zoomScale = { 1.0f },
            onZoomChange = { _, _, _, _ -> },
            onResetZoom = {},
            onSettleZoom = {},
            onSingleTap = {},
            onDoubleTapSeek = {},
            onDoubleTapPlayPause = {},
            onFastForwardStart = {},
            onFastForwardEnd = {},
            onHudStateChange = {},
            areControlsVisible = { visible },
            controlsGestureGuardMetrics = { currentMetrics },
        )

        assertFalse(callbacks.areControlsVisible())
        assertEquals(ControlsGestureGuardMetrics.None, callbacks.getControlsGestureGuardMetrics())

        val updatedMetrics = computeControlsGestureGuardMetrics(
            density = density,
            statusBarTopPx = 0,
            cutoutTopPx = 0,
            cutoutLeftPx = 0,
            cutoutRightPx = 0,
            navBarBottomPx = 0,
            landscapeHorizontalInsetDp = 28f,
        )
        visible = true
        currentMetrics = updatedMetrics

        assertTrue(callbacks.areControlsVisible())
        assertEquals(updatedMetrics, callbacks.getControlsGestureGuardMetrics())
    }
}
