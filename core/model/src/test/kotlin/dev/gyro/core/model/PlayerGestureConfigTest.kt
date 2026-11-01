package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGestureConfigTest {

    @Test
    fun testDefaultConfigAlignsWithMxPlayerStandards() {
        val config = PlayerGestureConfig()

        // Toggles
        assertFalse("isLocked defaults to false", config.isLocked)
        assertTrue("isVolumeEnabled defaults to true", config.isVolumeEnabled)
        assertTrue("isBrightnessEnabled defaults to true", config.isBrightnessEnabled)
        assertTrue("isSeekEnabled defaults to true", config.isSeekEnabled)
        assertTrue("isZoomEnabled defaults to true", config.isZoomEnabled)
        assertTrue("isDoubleTapSeekEnabled defaults to true", config.isDoubleTapSeekEnabled)
        assertTrue("isDoubleTapPlayPauseEnabled defaults to true", config.isDoubleTapPlayPauseEnabled)
        assertTrue("isLongPressSpeedEnabled defaults to true", config.isLongPressSpeedEnabled)
        assertFalse("swapVolumeAndBrightness defaults to false", config.swapVolumeAndBrightness)

        // Values & Sensitivities
        assertEquals(10, config.doubleTapSeekSeconds)
        assertEquals(2.0f, config.longPressSpeed, 0.001f)
        assertEquals(72f, config.edgeExclusionPx, 0.001f)
        assertEquals(1.0f, config.volumeSensitivity, 0.001f)
        assertEquals(1.0f, config.brightnessSensitivity, 0.001f)

        // Timeouts
        assertEquals(450L, config.longPressTimeoutMs)
        assertEquals(320L, config.doubleTapTimeoutMs)
        assertEquals(260L, config.singleTapDelayMs)
    }

    @Test
    fun testCopyBehaviorForCustomSettings() {
        val defaults = PlayerGestureConfig()
        val custom = defaults.copy(
            isVolumeEnabled = false,
            swapVolumeAndBrightness = true,
            doubleTapSeekSeconds = 15,
            longPressSpeed = 3.0f,
            volumeSensitivity = 1.5f,
            brightnessSensitivity = 0.8f
        )

        assertFalse(custom.isVolumeEnabled)
        assertTrue(custom.isBrightnessEnabled) // Unchanged
        assertTrue(custom.swapVolumeAndBrightness)
        assertEquals(15, custom.doubleTapSeekSeconds)
        assertEquals(3.0f, custom.longPressSpeed, 0.001f)
        assertEquals(1.5f, custom.volumeSensitivity, 0.001f)
        assertEquals(0.8f, custom.brightnessSensitivity, 0.001f)
    }

    @Test
    fun testEqualityAndHashCode() {
        val config1 = PlayerGestureConfig()
        val config2 = PlayerGestureConfig()
        val config3 = PlayerGestureConfig(doubleTapSeekSeconds = 30)

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertFalse(config1 == config3)
    }
}
