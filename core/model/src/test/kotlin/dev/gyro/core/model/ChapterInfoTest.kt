package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterInfoTest {

    @Test
    fun testChapterInfoProperties() {
        val chapter = ChapterInfo(index = 0, title = "Prologue", timeMs = 12_500L)
        assertEquals(0, chapter.index)
        assertEquals("Prologue", chapter.title)
        assertEquals(12_500L, chapter.timeMs)
    }

    @Test
    fun testFormattedTimeUnderOneHour() {
        val chapter = ChapterInfo(0, "Intro", 83_000L) // 1m 23s
        assertEquals("01:23", chapter.formattedTime)
    }

    @Test
    fun testFormattedTimeOverOneHour() {
        val chapter = ChapterInfo(1, "Part 2", 3_723_000L) // 1h 2m 3s
        assertEquals("1:02:03", chapter.formattedTime)
    }

    @Test
    fun testFormattedTimeZero() {
        val chapter = ChapterInfo(0, "Start", 0L)
        assertEquals("00:00", chapter.formattedTime)
    }

    @Test
    fun testFormattedTimeNegativeClamping() {
        val chapter = ChapterInfo(0, "Before Start", -5_000L)
        assertEquals("00:00", chapter.formattedTime)
    }

    @Test
    fun testChapterOrderingByTimeAndIndex() {
        val ch1 = ChapterInfo(index = 0, title = "Intro", timeMs = 0L)
        val ch2 = ChapterInfo(index = 1, title = "Act I", timeMs = 120_000L)
        val ch3 = ChapterInfo(index = 2, title = "Act II", timeMs = 360_000L)
        val ch4 = ChapterInfo(index = 3, title = "Credits", timeMs = 900_000L)

        val shuffled = listOf(ch3, ch1, ch4, ch2)

        val sortedByTime = shuffled.sortedBy { it.timeMs }
        assertEquals(listOf(ch1, ch2, ch3, ch4), sortedByTime)

        val sortedByIndex = shuffled.sortedBy { it.index }
        assertEquals(listOf(ch1, ch2, ch3, ch4), sortedByIndex)
    }

    @Test
    fun testChapterEqualityAndCopy() {
        val original = ChapterInfo(index = 1, title = "Scene 1", timeMs = 45_000L)
        val copySame = original.copy()
        val copyModified = original.copy(title = "Scene 1 (Extended)")

        assertEquals(original, copySame)
        assertEquals(original.hashCode(), copySame.hashCode())
        assertEquals("Scene 1 (Extended)", copyModified.title)
        assertEquals(45_000L, copyModified.timeMs)
    }
}
