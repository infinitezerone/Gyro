package dev.gyro.feature.player

import dev.gyro.core.model.ChapterInfo
import dev.gyro.feature.player.component.snapToChapter
import dev.gyro.feature.player.component.snapToChapterResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChapterTest {

    // Helper simulating PlayerScrubBar fraction calculation
    private fun calculateChapterFractions(chapters: List<ChapterInfo>, durationMs: Long): List<Float> {
        if (durationMs <= 0L || chapters.isEmpty()) return emptyList()
        val durFloat = durationMs.toFloat()
        return chapters.mapNotNull { ch ->
            if (ch.timeMs > 0L && ch.timeMs < durationMs) {
                (ch.timeMs.toFloat() / durFloat).coerceIn(0f, 1f)
            } else null
        }
    }

    // Helper simulating active chapter index resolution in PlayerTrackSelectionSheet
    private fun resolveActiveChapter(
        chapters: List<ChapterInfo>,
        currentChapterIndex: Int,
        currentTimePosMs: Long
    ): Int {
        if (chapters.isEmpty()) return -1
        if (currentChapterIndex in chapters.indices) return currentChapterIndex
        return chapters.indexOfLast { it.timeMs <= currentTimePosMs }.coerceAtLeast(0)
    }

    @Test
    fun testChapterFractions_normalChapters() {
        val chapters = listOf(
            ChapterInfo(0, "Intro", 0L),
            ChapterInfo(1, "Part 1", 30_000L),
            ChapterInfo(2, "Part 2", 60_000L),
            ChapterInfo(3, "Credits", 90_000L)
        )
        val fractions = calculateChapterFractions(chapters, 120_000L)
        assertEquals(3, fractions.size)
        assertEquals(0.25f, fractions[0], 0.001f)
        assertEquals(0.50f, fractions[1], 0.001f)
        assertEquals(0.75f, fractions[2], 0.001f)
    }

    @Test
    fun testChapterFractions_zeroOrNegativeDurationGuards() {
        val chapters = listOf(ChapterInfo(0, "Ch1", 10_000L))
        assertTrue(calculateChapterFractions(chapters, 0L).isEmpty())
        assertTrue(calculateChapterFractions(chapters, -5000L).isEmpty())
    }

    @Test
    fun testChapterFractions_emptyChapters() {
        assertTrue(calculateChapterFractions(emptyList(), 100_000L).isEmpty())
    }

    @Test
    fun testChapterFractions_outOfBoundsTimestampsFiltered() {
        val chapters = listOf(
            ChapterInfo(0, "Zero", 0L),
            ChapterInfo(1, "Negative", -10_000L),
            ChapterInfo(2, "Valid", 50_000L),
            ChapterInfo(3, "AtDuration", 100_000L),
            ChapterInfo(4, "Beyond", 120_000L)
        )
        val fractions = calculateChapterFractions(chapters, 100_000L)
        assertEquals(1, fractions.size)
        assertEquals(0.5f, fractions[0], 0.001f)
    }

    @Test
    fun testResolveActiveChapter_validEngineIndex() {
        val chapters = listOf(
            ChapterInfo(0, "A", 0L),
            ChapterInfo(1, "B", 30_000L),
            ChapterInfo(2, "C", 60_000L)
        )
        assertEquals(1, resolveActiveChapter(chapters, currentChapterIndex = 1, currentTimePosMs = 0L))
    }

    @Test
    fun testResolveActiveChapter_fallbackPositionMatching() {
        val chapters = listOf(
            ChapterInfo(0, "A", 0L),
            ChapterInfo(1, "B", 30_000L),
            ChapterInfo(2, "C", 60_000L)
        )
        // Position at 0s -> Ch 0
        assertEquals(0, resolveActiveChapter(chapters, -1, 0L))
        // Position at 25s -> Ch 0
        assertEquals(0, resolveActiveChapter(chapters, -1, 25_000L))
        // Position at 30s -> Ch 1
        assertEquals(1, resolveActiveChapter(chapters, -1, 30_000L))
        // Position at 45s -> Ch 1
        assertEquals(1, resolveActiveChapter(chapters, -1, 45_000L))
        // Position at 75s -> Ch 2
        assertEquals(2, resolveActiveChapter(chapters, -1, 75_000L))
    }

    @Test
    fun testChapterFormattedTime() {
        assertEquals("00:00", ChapterInfo(0, "T", 0L).formattedTime)
        assertEquals("01:05", ChapterInfo(1, "T", 65_000L).formattedTime)
        assertEquals("1:02:03", ChapterInfo(2, "T", 3_723_000L).formattedTime)
    }

    @Test
    fun testSnapToChapter_withinRadius_returnsChapter() {
        val width = 1000f
        val radius = 20f
        val chapters = listOf(0.25f, 0.5f, 0.75f)
        // 8px from the 0.5 chapter -> snaps to it
        assertEquals(0.5f, snapToChapter(508f, width, chapters, radius), 0.0001f)
        // exactly on the 0.75 chapter
        assertEquals(0.75f, snapToChapter(750f, width, chapters, radius), 0.0001f)
    }

    @Test
    fun testSnapToChapter_outsideRadius_returnsRaw() {
        val width = 1000f
        val radius = 20f
        val chapters = listOf(0.25f, 0.5f, 0.75f)
        // 40px away from 0.5 -> no snap
        assertEquals(0.54f, snapToChapter(540f, width, chapters, radius), 0.0001f)
        assertEquals(0.1f, snapToChapter(100f, width, chapters, radius), 0.0001f)
    }

    @Test
    fun testSnapToChapter_picksClosest() {
        val width = 1000f
        val radius = 60f
        // two chapters 30px apart, finger between -> picks the nearer one
        val chapters = listOf(0.30f, 0.33f)
        assertEquals(0.33f, snapToChapter(321f, width, chapters, radius), 0.0001f)
        assertEquals(0.30f, snapToChapter(309f, width, chapters, radius), 0.0001f)
    }

    @Test
    fun testSnapToChapter_emptyAndBounds() {
        val width = 1000f
        // no chapters -> raw position
        assertEquals(0.42f, snapToChapter(420f, width, emptyList(), 20f), 0.0001f)
        // extremes clamp
        val chapters = listOf(0.02f, 0.98f)
        assertEquals(0.02f, snapToChapter(5f, width, chapters, 20f), 0.0001f)
        assertEquals(0.98f, snapToChapter(995f, width, chapters, 20f), 0.0001f)
    }

    @Test
    fun testSnapChapterIndex_forHapticsOnlyOnCrossing() {
        val width = 1000f
        val radius = 20f
        val chapters = listOf(0.25f, 0.5f, 0.75f)

        // sitting on chapter 1 -> index 1
        assertEquals(1, snapToChapterResult(500f, width, chapters, radius).chapterIndex)
        // still within chapter 1's radius -> same index (no repeated buzz)
        assertEquals(1, snapToChapterResult(510f, width, chapters, radius).chapterIndex)
        // moved onto chapter 2 -> index changes
        assertEquals(2, snapToChapterResult(750f, width, chapters, radius).chapterIndex)
        // outside all radii -> -1
        assertEquals(-1, snapToChapterResult(620f, width, chapters, radius).chapterIndex)
    }
}
