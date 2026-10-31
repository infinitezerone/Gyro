package dev.gyro.player.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGestureTest {

    @Test
    fun testDefaultConfigAlignsWithMxPlayerStandards() {
        val config = PlayerGestureConfig()
        assertFalse(config.isLocked)
        assertTrue(config.isVolumeEnabled)
        assertTrue(config.isBrightnessEnabled)
        assertTrue(config.isSeekEnabled)
        assertTrue(config.isZoomEnabled)
        assertTrue(config.isDoubleTapSeekEnabled)
        assertTrue(config.isDoubleTapPlayPauseEnabled)
        assertTrue(config.isLongPressSpeedEnabled)
        assertFalse(config.swapVolumeAndBrightness)
        assertEquals(10, config.doubleTapSeekSeconds)
        assertEquals(2.0f, config.longPressSpeed, 0.001f)
        assertEquals(72f, config.edgeExclusionPx, 0.001f)
        assertEquals(30f, config.volumeSensitivityPx, 0.001f)
        assertEquals(400f, config.brightnessSensitivityPx, 0.001f)
        assertEquals(450L, config.longPressTimeoutMs)
        assertEquals(320L, config.doubleTapTimeoutMs)
        assertEquals(260L, config.singleTapDelayMs)
    }

    @Test
    fun testCustomConfigTogglesAndSwapping() {
        val config = PlayerGestureConfig(
            isVolumeEnabled = false,
            swapVolumeAndBrightness = true,
            doubleTapSeekSeconds = 15,
            longPressSpeed = 3.0f,
            volumeSensitivityPx = 40f
        )
        assertFalse(config.isVolumeEnabled)
        assertTrue(config.swapVolumeAndBrightness)
        assertEquals(15, config.doubleTapSeekSeconds)
        assertEquals(3.0f, config.longPressSpeed, 0.001f)
        assertEquals(40f, config.volumeSensitivityPx, 0.001f)
    }

    @Test
    fun testPlayerGestureElementLifecycleAndEquality() {
        val config1 = PlayerGestureConfig(isLocked = false)
        val config2 = PlayerGestureConfig(isLocked = true)

        var singleTapCount = 0
        val callbacks1 = object : PlayerGestureCallbacks {
            override fun onSingleTap() { singleTapCount++ }
        }
        val callbacks2 = object : PlayerGestureCallbacks {
            override fun onSingleTap() { singleTapCount += 2 }
        }

        val element1 = PlayerGestureElement(config1, callbacks1)
        val element2 = PlayerGestureElement(config1, callbacks1)
        val element3 = PlayerGestureElement(config2, callbacks2)

        assertEquals(element1, element2)
        assertEquals(element1.hashCode(), element2.hashCode())
        assertFalse(element1 == element3)

        // Node creation and update
        val node = element1.create()
        assertEquals(config1, node.config)

        element3.update(node)
        assertEquals(config2, node.config)
    }

    @Test
    fun testDefaultPlayerGestureCallbacksSeekMath() {
        var relativeSeekDelta = 0L
        var hudState: GestureHudState = GestureHudState.Idle
        val scrubState = PlayerScrubState()

        val callbacks = DefaultPlayerGestureCallbacks(
            context = null,
            currentPosMs = { 10_000L },
            durationMs = { 100_000L },
            scrubState = scrubState,
            zoomScale = { 1.0f },
            onZoomChange = { _, _, _, _ -> },
            onResetZoom = {},
            onSettleZoom = {},
            onSingleTap = {},
            onDoubleTapSeek = { delta -> relativeSeekDelta = delta },
            onDoubleTapPlayPause = {},
            onFastForwardStart = {},
            onFastForwardEnd = {},
            onHudStateChange = { state -> hudState = state }
        )

        // Start scrub at 10s of 100s -> fraction = 0.1
        callbacks.onSeekStart(10_000L)
        assertTrue(scrubState.isActive)
        assertEquals(0.1f, scrubState.fraction, 0.001f)

        // Update scrub to 50s -> fraction = 0.5
        callbacks.onSeekUpdate(50_000L, 40_000L, 100_000L)
        assertEquals(0.5f, scrubState.fraction, 0.001f)
        assertTrue(hudState is GestureHudState.Seek)
        val seekHud = hudState as GestureHudState.Seek
        assertEquals(50_000L, seekHud.targetMs)
        assertEquals(40_000L, seekHud.deltaMs)
        assertEquals(100_000L, seekHud.totalMs)

        // End scrub at 50s -> commits relative seek of 50s - 10s = +40s
        callbacks.onSeekEnd(50_000L)
        assertFalse(scrubState.isActive)
        assertEquals(40_000L, relativeSeekDelta)
    }

    @Test
    fun testDefaultPlayerGestureCallbacksZoomDelegation() {
        var recordedZoom = 0f
        var recordedPan = Offset.Zero
        var recordedCentroid = Offset.Zero
        var recordedIsPinch = false
        var resetZoomCalled = false
        var settleZoomCalled = false

        val callbacks = DefaultPlayerGestureCallbacks(
            context = null,
            currentPosMs = { 0L },
            durationMs = { 0L },
            scrubState = null,
            zoomScale = { 2.5f },
            onZoomChange = { zoom, pan, centroid, isPinch ->
                recordedZoom = zoom
                recordedPan = pan
                recordedCentroid = centroid
                recordedIsPinch = isPinch
            },
            onResetZoom = { resetZoomCalled = true },
            onSettleZoom = { settleZoomCalled = true },
            onSingleTap = {},
            onDoubleTapSeek = {},
            onDoubleTapPlayPause = {},
            onFastForwardStart = {},
            onFastForwardEnd = {},
            onHudStateChange = {}
        )

        assertEquals(2.5f, callbacks.getZoomScale(), 0.001f)

        callbacks.onZoomPan(1.2f, Offset(10f, 20f), Offset(300f, 400f), true)
        assertEquals(1.2f, recordedZoom, 0.001f)
        assertEquals(Offset(10f, 20f), recordedPan)
        assertEquals(Offset(300f, 400f), recordedCentroid)
        assertTrue(recordedIsPinch)

        callbacks.onResetZoom()
        assertTrue(resetZoomCalled)

        callbacks.onSettleZoom()
        assertTrue(settleZoomCalled)
    }
}
