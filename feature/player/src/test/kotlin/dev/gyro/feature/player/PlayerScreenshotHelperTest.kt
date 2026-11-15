package dev.gyro.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenshotHelperTest {

    @Test
    fun buildFilename_cleansSpecialCharactersAndWhitespace() {
        val filename = PlayerScreenshotController.buildFilename(
            mediaTitle = "Episode 01: The Beginning? <HD>/[1080p]*",
            posMs = 3661000L, // 1h 1m 1s
            timestampMs = 1731660000000L
        )
        // Colons, spaces, brackets, slashes, asterisks replaced by underscores
        assertTrue(filename.startsWith("Gyro_Episode_01__The_Beginning___H"))
        assertTrue(filename.contains("01h01m01s"))
        assertTrue(filename.endsWith("1731660000000.jpg"))
    }

    @Test
    fun buildFilename_handlesBlankTitleWithFallback() {
        val filename = PlayerScreenshotController.buildFilename(
            mediaTitle = "   ",
            posMs = 65000L, // 1m 5s
            timestampMs = 1731660000000L
        )
        assertEquals("Gyro_Snapshot_01m05s_1731660000000.jpg", filename)
    }

    @Test
    fun formatPosition_formatsMinutesAndHoursCorrectly() {
        assertEquals("00m00s", PlayerScreenshotController.formatPosition(0L))
        assertEquals("05m23s", PlayerScreenshotController.formatPosition(323000L))
        assertEquals("02h15m30s", PlayerScreenshotController.formatPosition(8130000L))
    }
}
