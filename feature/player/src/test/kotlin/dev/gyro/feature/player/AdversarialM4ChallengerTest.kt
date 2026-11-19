package dev.gyro.feature.player

import dev.gyro.core.model.ChapterInfo
import dev.gyro.feature.player.gesture.VolumeGestureCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AdversarialM4ChallengerTest {

    private val maxSysVolume = 15

    // =========================================================================
    // 1. VolumeGestureCalculator: Exact Boundary States
    // =========================================================================

    @Test
    fun testExactBoundary_0Percent() {
        val result = VolumeGestureCalculator.calculate(
            dragStartPercent = 0,
            deltaRatio = 0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(0, result.totalPercent)
        assertEquals(0, result.systemVolume)
        assertEquals(0.0f, result.volumeRatio, 0.0001f)
    }

    @Test
    fun testExactBoundary_100Percent() {
        val result = VolumeGestureCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(100, result.totalPercent)
        assertEquals(maxSysVolume, result.systemVolume)
        assertEquals(1.0f, result.volumeRatio, 0.0001f)
    }

    @Test
    fun testDeltaCannotExceedSystemMaximum() {
        val result = VolumeGestureCalculator.calculate(
            dragStartPercent = 100,
            deltaRatio = 1.0f,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(100, result.totalPercent)
        assertEquals(maxSysVolume, result.systemVolume)
        assertEquals(1.0f, result.volumeRatio, 0.0001f)
    }

    // =========================================================================
    // 2. Linear Mapping Across Stream Volumes
    // =========================================================================

    @Test
    fun testSystemVolumeMapping_allPercentsAndMaxima() {
        val testMaxVolumes = listOf(1, 5, 10, 15, 25, 100)
        for (maxVol in testMaxVolumes) {
            for (pct in 0..100) {
                val result = VolumeGestureCalculator.calculate(
                    dragStartPercent = pct,
                    deltaRatio = 0f,
                    maxSystemVolume = maxVol
                )
                val expected = (pct / 100f * maxVol).roundToInt().coerceIn(0, maxVol)
                assertEquals("System volume at $pct% (max=$maxVol)", expected, result.systemVolume)
                assertEquals("Ratio at $pct%", pct / 100f, result.volumeRatio, 0.0001f)
            }
        }
    }

    // =========================================================================
    // 3. Reverse Swipe: Continuous Sweep from 100% Down to 0%
    // =========================================================================

    @Test
    fun testReverseSwipe_continuousDescentFrom100To0() {
        var lastSystemVolume = maxSysVolume
        for (step in 0..100) {
            val result = VolumeGestureCalculator.calculate(
                dragStartPercent = 100,
                deltaRatio = -step / 100f,
                maxSystemVolume = maxSysVolume
            )
            val expectedPct = 100 - step
            assertEquals("Total percent match at step $step", expectedPct, result.totalPercent)
            assertTrue(
                "System volume should monotonically non-increase: $result.systemVolume <= $lastSystemVolume",
                result.systemVolume <= lastSystemVolume
            )
            assertEquals("Ratio must follow percent", expectedPct / 100f, result.volumeRatio, 0.0001f)
            lastSystemVolume = result.systemVolume
        }
        assertEquals(0, lastSystemVolume)
    }

    // =========================================================================
    // 4. Extreme Inputs & Stress
    // =========================================================================

    @Test
    fun testExtremeInputs_largeDeltas() {
        val fullDown = VolumeGestureCalculator.calculate(100, -100f, maxSysVolume)
        assertEquals(0, fullDown.totalPercent)
        assertEquals(0, fullDown.systemVolume)

        val fullUp = VolumeGestureCalculator.calculate(0, 100f, maxSysVolume)
        assertEquals(100, fullUp.totalPercent)
        assertEquals(maxSysVolume, fullUp.systemVolume)
    }

    @Test
    fun testExtremeInputs_maxSystemVolumeZeroOrNegative() {
        val nonPositiveMax = listOf(0, -1, -15, -100)
        for (maxVol in nonPositiveMax) {
            val res0 = VolumeGestureCalculator.calculate(0, 0f, maxVol)
            assertEquals(0, res0.systemVolume)

            val res100 = VolumeGestureCalculator.calculate(100, 0f, maxVol)
            // safeMax coerces to 1, so system volume clamps cleanly at 1
            assertEquals(1, res100.systemVolume)

            val initialPct = VolumeGestureCalculator.computeInitialPercent(0, maxVol)
            assertEquals(0, initialPct)
        }
    }

    @Test
    fun testExtremeInputs_nanDeltaRatio() {
        // Defensive arithmetic handles Float.NaN safely without throwing, producing 0 delta
        val result = VolumeGestureCalculator.calculate(
            dragStartPercent = 80,
            deltaRatio = Float.NaN,
            maxSystemVolume = maxSysVolume
        )
        assertEquals(80, result.totalPercent)
    }

    @Test
    fun testExtremeInputs_infinityDeltaRatio() {
        val posInf = VolumeGestureCalculator.calculate(0, Float.POSITIVE_INFINITY, maxSysVolume)
        assertEquals(100, posInf.totalPercent)

        val negInf = VolumeGestureCalculator.calculate(100, Float.NEGATIVE_INFINITY, maxSysVolume)
        assertEquals(0, negInf.totalPercent)
    }

    @Test
    fun testComputeInitialPercent_clampsOutOfRangeSystemVolume() {
        assertEquals(0, VolumeGestureCalculator.computeInitialPercent(-5, maxSysVolume))
        assertEquals(100, VolumeGestureCalculator.computeInitialPercent(99, maxSysVolume))
        assertEquals(40, VolumeGestureCalculator.computeInitialPercent(6, maxSysVolume))
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
