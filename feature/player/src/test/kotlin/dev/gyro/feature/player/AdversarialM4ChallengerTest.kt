package dev.gyro.feature.player

import dev.gyro.core.model.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AdversarialM4ChallengerTest {

    private val maxSysVolume = 15

    // =========================================================================
    // 1. TwoStageVolumeCalculator: Exact Boundary States
    // =========================================================================

    @Test
    fun testExactBoundary_0Percent() {
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 0,
            deltaRatio = 0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(0, result.totalPercent)
        assertEquals(0, result.systemVolume)
        assertEquals(100.0, result.softwareVolume, 0.0001)
        assertEquals(0.0f, result.volumeRatio, 0.0001f)
        assertFalse(result.isOverdrive)
    }

    @Test
    fun testExactBoundary_100Percent() {
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(100, result.totalPercent)
        assertEquals(maxSysVolume, result.systemVolume)
        assertEquals(100.0, result.softwareVolume, 0.0001)
        assertEquals(1.0f, result.volumeRatio, 0.0001f)
        assertFalse(result.isOverdrive)
    }

    @Test
    fun testExactBoundary_101Percent() {
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 0.01f, // +1%
            maxSystemVolume = maxSysVolume
        )
        assertEquals(101, result.totalPercent)
        assertEquals(maxSysVolume, result.systemVolume)
        assertEquals(101.0, result.softwareVolume, 0.0001)
        assertEquals(0.01f, result.volumeRatio, 0.0001f)
        assertTrue(result.isOverdrive)
    }

    @Test
    fun testExactBoundary_200Percent() {
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = 200,
            deltaRatio = 0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(200, result.totalPercent)
        assertEquals(maxSysVolume, result.systemVolume)
        assertEquals(200.0, result.softwareVolume, 0.0001)
        assertEquals(1.0f, result.volumeRatio, 0.0001f)
        assertTrue(result.isOverdrive)
    }

    // =========================================================================
    // 2. System Volume Isolation Across 101%..200%
    // =========================================================================

    @Test
    fun testSystemVolumeIsolation_allOverdrivePercents() {
        val testMaxVolumes = listOf(1, 5, 10, 15, 25, 100)
        for (maxVol in testMaxVolumes) {
            for (pct in 101..200) {
                val result = TwoStageVolumeCalculator.calculate(
                    dragStartPercent = pct,
                    deltaRatio = 0f,
                    maxSystemVolume = maxVol
                )
                // System volume MUST be clamped at maxVol and NEVER change
                assertEquals("System volume must remain at maxVol for $pct% (max=$maxVol)", maxVol, result.systemVolume)
                assertTrue("isOverdrive must be true for $pct%", result.isOverdrive)
                assertEquals("Software gain must be equal to pct", pct.toDouble(), result.softwareVolume, 0.0001)
                val expectedRatio = (pct - 100) / 100f
                assertEquals("Overdrive ratio must scale linearly", expectedRatio, result.volumeRatio, 0.0001f)
            }
        }
    }

    // =========================================================================
    // 3. Reverse Swipe: Continuous Sweep from 200% Down to 0%
    // =========================================================================

    @Test
    fun testReverseSwipe_continuousDescentFrom200To0() {
        var lastSystemVolume = maxSysVolume
        for (step in 0..200) {
            val deltaRatio = -step / 100f
            val result = TwoStageVolumeCalculator.calculate(
                dragStartPercent = 200,
                deltaRatio = deltaRatio,
                maxSystemVolume = maxSysVolume
            )
            val expectedPct = 200 - step
            assertEquals("Total percent match at step $step", expectedPct, result.totalPercent)

            if (expectedPct > 100) {
                // In Overdrive: system volume strictly at maxSysVolume, software volume scales down
                assertEquals(maxSysVolume, result.systemVolume)
                assertEquals(expectedPct.toDouble(), result.softwareVolume, 0.0001)
                assertTrue(result.isOverdrive)
            } else {
                // In Stage 1: software volume pinned at 100.0%, system volume monotonically non-increasing
                assertEquals("Software volume must be 100.0% at $expectedPct%", 100.0, result.softwareVolume, 0.0001)
                assertFalse("isOverdrive must be false at $expectedPct%", result.isOverdrive)
                assertTrue(
                    "System volume should monotonically non-increase: $result.systemVolume <= $lastSystemVolume",
                    result.systemVolume <= lastSystemVolume
                )
                lastSystemVolume = result.systemVolume
            }
        }
        assertEquals(0, lastSystemVolume)
    }

    // =========================================================================
    // 4. Extreme Inputs & Stress
    // =========================================================================

    @Test
    fun testExtremeInputs_largeDeltas() {
        // -100f delta from 100%
        val resNeg100 = TwoStageVolumeCalculator.calculate(100, -100f, maxSysVolume)
        assertEquals(0, resNeg100.totalPercent)
        assertEquals(0, resNeg100.systemVolume)
        assertEquals(100.0, resNeg100.softwareVolume, 0.0001)
        assertFalse(resNeg100.isOverdrive)

        // +100f delta from 100%
        val resPos100 = TwoStageVolumeCalculator.calculate(100, 100f, maxSysVolume)
        assertEquals(200, resPos100.totalPercent)
        assertEquals(maxSysVolume, resPos100.systemVolume)
        assertEquals(200.0, resPos100.softwareVolume, 0.0001)
        assertTrue(resPos100.isOverdrive)
    }

    @Test
    fun testExtremeInputs_maxSystemVolumeZeroOrNegative() {
        val nonPositiveMax = listOf(0, -1, -15, -100)
        for (maxVol in nonPositiveMax) {
            val res0 = TwoStageVolumeCalculator.calculate(0, 0f, maxVol)
            assertEquals(0, res0.systemVolume)
            assertFalse(res0.isOverdrive)

            val res100 = TwoStageVolumeCalculator.calculate(100, 0f, maxVol)
            // safeMax coerces to 1, so system volume clamps cleanly at 1
            assertEquals(1, res100.systemVolume)
            assertFalse(res100.isOverdrive)

            val res200 = TwoStageVolumeCalculator.calculate(200, 0f, maxVol)
            assertEquals(1, res200.systemVolume)
            assertTrue(res200.isOverdrive)

            val initialPct = TwoStageVolumeCalculator.computeInitialPercent(0, maxVol, 100.0)
            assertEquals(0, initialPct)
        }
    }

    @Test
    fun testExtremeInputs_nanDeltaRatio() {
        // In Kotlin's roundToInt(), Float.NaN throws IllegalArgumentException("Cannot round NaN value.")
        try {
            TwoStageVolumeCalculator.calculate(
                dragStartPercent = 80,
                deltaRatio = Float.NaN,
                maxSystemVolume = maxSysVolume
            )
            org.junit.Assert.fail("Expected IllegalArgumentException for Float.NaN in TwoStageVolumeCalculator.calculate")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cannot round NaN value.", e.message)
        }

        // computeInitialPercent with Double.NaN also throws IllegalArgumentException
        try {
            TwoStageVolumeCalculator.computeInitialPercent(
                currentSystemVolume = 15,
                maxSystemVolume = maxSysVolume,
                currentSoftwareVolume = Double.NaN
            )
            org.junit.Assert.fail("Expected IllegalArgumentException for Double.NaN in TwoStageVolumeCalculator.computeInitialPercent")
        } catch (e: IllegalArgumentException) {
            assertEquals("Cannot round NaN value.", e.message)
        }
    }

    @Test
    fun testExtremeInputs_infinityDeltaRatio() {
        // Math.round(Float.POSITIVE_INFINITY) returns Integer.MAX_VALUE (2147483647).
        // 100 + Integer.MAX_VALUE overflows to -2147483549, which .coerceIn(0, 200) clamps to 0!
        val posInf = TwoStageVolumeCalculator.calculate(100, Float.POSITIVE_INFINITY, maxSysVolume)
        assertEquals(0, posInf.totalPercent)

        // Math.round(Float.NEGATIVE_INFINITY) returns Integer.MIN_VALUE (-2147483648).
        // 100 + Integer.MIN_VALUE overflows to -2147483548, which .coerceIn(0, 200) clamps to 0.
        val negInf = TwoStageVolumeCalculator.calculate(100, Float.NEGATIVE_INFINITY, maxSysVolume)
        assertEquals(0, negInf.totalPercent)
    }

    // =========================================================================
    // 5. Chapter Micro-Ticks Math & Stress
    // =========================================================================

    private fun computeChapterFractions(chapters: List<ChapterInfo>, durationMs: Long): List<Float> {
        if (durationMs <= 0L || chapters.isEmpty()) {
            return emptyList()
        }
        val durFloat = durationMs.toFloat()
        return chapters.mapNotNull { ch ->
            if (ch.timeMs > 0L && ch.timeMs < durationMs) {
                (ch.timeMs.toFloat() / durFloat).coerceIn(0f, 1f)
            } else null
        }
    }

    @Test
    fun testChapterTicks_boundaryCases() {
        val duration = 100_000L
        val chapters = listOf(
            ChapterInfo(0, "Before 0", -5000L),
            ChapterInfo(1, "At 0", 0L),
            ChapterInfo(2, "Middle 1", 25_000L),
            ChapterInfo(3, "Middle 2", 75_000L),
            ChapterInfo(4, "At Duration", 100_000L),
            ChapterInfo(5, "Beyond Duration", 150_000L)
        )

        val fractions = computeChapterFractions(chapters, duration)
        assertEquals("Only middle chapters strictly inside (0, duration) must be included", 2, fractions.size)
        assertEquals(0.25f, fractions[0], 0.0001f)
        assertEquals(0.75f, fractions[1], 0.0001f)
    }

    @Test
    fun testChapterTicks_zeroAndNegativeDuration() {
        val chapters = listOf(ChapterInfo(0, "Mid", 5000L))
        assertTrue(computeChapterFractions(chapters, 0L).isEmpty())
        assertTrue(computeChapterFractions(chapters, -100L).isEmpty())
        assertTrue(computeChapterFractions(chapters, Long.MIN_VALUE).isEmpty())
    }

    @Test
    fun testChapterTicks_emptyChapters() {
        assertTrue(computeChapterFractions(emptyList(), 50_000L).isEmpty())
    }

    @Test
    fun testChapterTicks_1000ChaptersStress() {
        val duration = 1_000_000L
        val chapters = (0 until 1000).map { i ->
            ChapterInfo(i, "Chapter $i", i * 1000L) // from 0L to 999_000L
        }

        val startNs = System.nanoTime()
        val fractions = computeChapterFractions(chapters, duration)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        // Chapter 0 (0L) is excluded, 1..999 are included
        assertEquals(999, fractions.size)
        assertEquals(0.001f, fractions[0], 0.0001f)
        assertEquals(0.999f, fractions[998], 0.0001f)
        println("1000 chapters fraction calculation took $elapsedMs ms")
        assertTrue("Execution must be well under 50ms", elapsedMs < 50.0)
    }
}
