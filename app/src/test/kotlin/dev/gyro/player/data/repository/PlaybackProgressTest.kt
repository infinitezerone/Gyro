package dev.gyro.player.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressTest {

    @Test
    fun testProgressFraction() {
        val item = PlaybackProgress(
            uriString = "content://media/external/video/media/1",
            positionMs = 30_000L,
            durationMs = 60_000L,
            lastPlayedTimestamp = 1000L
        )

        assertEquals(0.5f, item.progressFraction, 0.001f)
        assertFalse(item.isCompleted)
    }

    @Test
    fun testIsCompletedNearEnd() {
        // Within 5 seconds of end
        val nearEnd = PlaybackProgress(
            uriString = "content://media/external/video/media/2",
            positionMs = 57_000L,
            durationMs = 60_000L,
            lastPlayedTimestamp = 1000L
        )
        assertTrue(nearEnd.isCompleted)

        // Beyond 95%
        val over95Percent = PlaybackProgress(
            uriString = "content://media/external/video/media/3",
            positionMs = 960_000L,
            durationMs = 1_000_000L,
            lastPlayedTimestamp = 1000L
        )
        assertTrue(over95Percent.isCompleted)
    }

    @Test
    fun testIsNotCompletedEarly() {
        val early = PlaybackProgress(
            uriString = "content://media/external/video/media/4",
            positionMs = 10_000L,
            durationMs = 60_000L,
            lastPlayedTimestamp = 1000L
        )
        assertFalse(early.isCompleted)
    }

    @Test
    fun testZeroOrNegativeDuration() {
        val zeroDur = PlaybackProgress(
            uriString = "content://media/external/video/media/5",
            positionMs = 5_000L,
            durationMs = 0L,
            lastPlayedTimestamp = 1000L
        )
        assertEquals(0f, zeroDur.progressFraction, 0.001f)
        assertFalse(zeroDur.isCompleted)
    }
}
