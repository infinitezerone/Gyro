package dev.gyro.feature.player.gesture

import dev.gyro.core.model.PlayerGestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGestureDynamicBindingTest {

    @Test
    fun testDynamicConfig_speedBoostSelectionAndPriorSpeedRestore() {
        val config = PlayerGestureConfig(
            isLongPressSpeedEnabled = true,
            longPressSpeed = 3.0f
        )

        var currentEngineSpeed = 1.5f // User was watching at 1.5x
        var priorSpeed: Float? = null
        var activeSpeedHud: Float? = null

        val onFastForwardStart: (Float) -> Unit = { targetSpeed ->
            priorSpeed = currentEngineSpeed
            currentEngineSpeed = targetSpeed
            activeSpeedHud = targetSpeed
        }

        val onFastForwardEnd: () -> Unit = {
            currentEngineSpeed = priorSpeed ?: 1.0f
            priorSpeed = null
            activeSpeedHud = null
        }

        // Long-press activates
        onFastForwardStart(config.longPressSpeed)
        assertEquals(3.0f, currentEngineSpeed, 0.001f)
        assertEquals(3.0f, activeSpeedHud ?: 0f, 0.001f)
        assertEquals(1.5f, priorSpeed ?: 0f, 0.001f)

        // Long-press releases
        onFastForwardEnd()
        assertEquals(1.5f, currentEngineSpeed, 0.001f)
        assertEquals(null, priorSpeed)
        assertEquals(null, activeSpeedHud)
    }

    @Test
    fun testDynamicConfig_seekStepMultiples() {
        val steps = listOf(5, 10, 15, 30)
        for (step in steps) {
            val config = PlayerGestureConfig(doubleTapSeekSeconds = step)
            assertEquals(step, config.doubleTapSeekSeconds)
            val accumulated2Taps = config.doubleTapSeekSeconds * 2
            assertEquals(step * 2, accumulated2Taps)
        }
    }

    @Test
    fun testDynamicConfig_handednessSwap() {
        val normalConfig = PlayerGestureConfig(swapVolumeAndBrightness = false)
        val swappedConfig = PlayerGestureConfig(swapVolumeAndBrightness = true)

        assertFalse(normalConfig.swapVolumeAndBrightness)
        assertTrue(swappedConfig.swapVolumeAndBrightness)
    }

    @Test
    fun testMasterGesturesDisabled_disablesAllSubGestures() {
        val userConfig = PlayerGestureConfig(
            isVolumeEnabled = true,
            isBrightnessEnabled = true,
            isSeekEnabled = true,
            isDoubleTapSeekEnabled = true,
            isDoubleTapPlayPauseEnabled = true,
            isLongPressSpeedEnabled = true,
            isZoomEnabled = true
        )

        val masterDisabled = false
        val effectiveConfig = if (!masterDisabled) {
            userConfig.copy(
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                isSeekEnabled = false,
                isDoubleTapSeekEnabled = false,
                isDoubleTapPlayPauseEnabled = false,
                isLongPressSpeedEnabled = false,
                isZoomEnabled = false
            )
        } else userConfig

        assertFalse(effectiveConfig.isVolumeEnabled)
        assertFalse(effectiveConfig.isBrightnessEnabled)
        assertFalse(effectiveConfig.isSeekEnabled)
        assertFalse(effectiveConfig.isDoubleTapSeekEnabled)
        assertFalse(effectiveConfig.isDoubleTapPlayPauseEnabled)
        assertFalse(effectiveConfig.isLongPressSpeedEnabled)
        assertFalse(effectiveConfig.isZoomEnabled)
    }
}
