package dev.gyro.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStageVolumeCalculatorTest {

    private val maxSysVolume = 15

    @Test
    fun testComputeInitialPercent_belowMaxSystemVolume() {
        // System volume at 6/15 = 40%, software volume at 100%
        val initialPct = TwoStageVolumeCalculator.computeInitialPercent(
            currentSystemVolume = 6,
            maxSystemVolume = maxSysVolume,
            currentSoftwareVolume = 100.0
        )
        assertEquals(40, initialPct)
    }

    @Test
    fun testComputeInitialPercent_atMaxSystemVolumeWithOverdrive() {
        // System volume at 15/15 = 100%, software volume at 160.0%
        val initialPct = TwoStageVolumeCalculator.computeInitialPercent(
            currentSystemVolume = 15,
            maxSystemVolume = maxSysVolume,
            currentSoftwareVolume = 160.0
        )
        assertEquals(160, initialPct)
    }

    @Test
    fun testStage1_systemVolumeScaling_below100() {
        // Start at 20% (system volume 3/15). Swipe up +0.4 (40%) -> 60%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 20,
            deltaRatio = 0.4f,
            maxSystemVolume = maxSysVolume
        )

        assertEquals(60, result.totalPercent)
        assertEquals(9, result.systemVolume) // 60% of 15 = 9
        assertEquals(100.0, result.softwareVolume, 0.001) // Locked at 100%
        assertEquals(0.6f, result.volumeRatio, 0.001f)
        assertFalse(result.isOverdrive)
    }

    @Test
    fun testStage1_boundaryAt100() {
        // Start at 80%. Swipe up +0.2 -> 100%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 80,
            deltaRatio = 0.2f,
            maxSystemVolume = maxSysVolume
        )

        assertEquals(100, result.totalPercent)
        assertEquals(15, result.systemVolume)
        assertEquals(100.0, result.softwareVolume, 0.001)
        assertEquals(1.0f, result.volumeRatio, 0.001f)
        assertFalse(result.isOverdrive)
    }

    @Test
    fun testStage2_overdriveGainScaling_above100_isolatedSystemVolume() {
        // Start at 100%. Swipe up +0.5 (50%) -> 150%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 0.5f,
            maxSystemVolume = maxSysVolume
        )

        assertEquals(150, result.totalPercent)
        assertEquals(15, result.systemVolume) // System volume strictly isolated at max (15)
        assertEquals(150.0, result.softwareVolume, 0.001) // Software gain 150%
        assertEquals(0.5f, result.volumeRatio, 0.001f) // Overdrive ratio 50%
        assertTrue(result.isOverdrive)
    }

    @Test
    fun testStage2_maxOverdriveAt200() {
        // Start at 100%. Swipe up +1.0 -> 200%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 1.0f,
            maxSystemVolume = maxSysVolume
        )

        assertEquals(200, result.totalPercent)
        assertEquals(15, result.systemVolume)
        assertEquals(200.0, result.softwareVolume, 0.001)
        assertEquals(1.0f, result.volumeRatio, 0.001f)
        assertTrue(result.isOverdrive)
    }

    @Test
    fun testReverseSwipe_fromOverdriveDownToSystemVolume() {
        // Start at 180% overdrive. Swipe down -1.2 (-120%) -> 60%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 180,
            deltaRatio = -1.2f,
            maxSystemVolume = maxSysVolume
        )

        assertEquals(60, result.totalPercent)
        assertEquals(9, result.systemVolume)
        assertEquals(100.0, result.softwareVolume, 0.001) // Software volume reset to 100%
        assertEquals(0.6f, result.volumeRatio, 0.001f)
        assertFalse(result.isOverdrive)
    }

    @Test
    fun testClamping_below0AndAbove200() {
        val clampedBottom = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 30,
            deltaRatio = -1.5f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(0, clampedBottom.totalPercent)
        assertEquals(0, clampedBottom.systemVolume)
        assertEquals(100.0, clampedBottom.softwareVolume, 0.001)
        assertFalse(clampedBottom.isOverdrive)

        val clampedTop = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 170,
            deltaRatio = 1.0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(200, clampedTop.totalPercent)
        assertEquals(15, clampedTop.systemVolume)
        assertEquals(200.0, clampedTop.softwareVolume, 0.001)
        assertTrue(clampedTop.isOverdrive)
    }

    @Test
    fun testComputeInitialPercent_withNaNSoftwareVolume() {
        // When software volume is NaN at max system volume, safely default to 100%
        val initialPct = TwoStageVolumeCalculator.computeInitialPercent(
            currentSystemVolume = 15,
            maxSystemVolume = maxSysVolume,
            currentSoftwareVolume = Double.NaN
        )
        assertEquals(100, initialPct)
    }

    @Test
    fun testCalculate_withNaNDeltaRatio() {
        // NaN deltaRatio should produce 0 delta, keeping dragStartPercent unchanged
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 75,
            deltaRatio = Float.NaN,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(75, result.totalPercent)
    }

    @Test
    fun testCalculate_withPositiveInfinityDeltaRatio() {
        // POSITIVE_INFINITY deltaRatio should saturate to maximum 200% overdrive
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 50,
            deltaRatio = Float.POSITIVE_INFINITY,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(200, result.totalPercent)
        assertEquals(15, result.systemVolume)
        assertEquals(200.0, result.softwareVolume, 0.001)
        assertTrue(result.isOverdrive)
    }

    @Test
    fun testCalculate_withNegativeInfinityDeltaRatio() {
        // NEGATIVE_INFINITY deltaRatio should saturate to minimum 0%
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 150,
            deltaRatio = Float.NEGATIVE_INFINITY,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(0, result.totalPercent)
        assertEquals(0, result.systemVolume)
        assertEquals(100.0, result.softwareVolume, 0.001)
        assertFalse(result.isOverdrive)
    }
}
