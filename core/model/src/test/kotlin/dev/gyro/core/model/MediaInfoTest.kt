package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInfoTest {

    @Test
    fun testFormattedFileSize() {
        val gbInfo = MediaInfo(fileSize = 4_294_967_296L)
        assertEquals("4.00 GB", gbInfo.formattedFileSize)

        val mbInfo = MediaInfo(fileSize = 104_857_600L)
        assertEquals("100.0 MB", mbInfo.formattedFileSize)

        val kbInfo = MediaInfo(fileSize = 512_000L)
        assertEquals("500 KB", kbInfo.formattedFileSize)

        val zeroInfo = MediaInfo(fileSize = 0L)
        assertEquals("--", zeroInfo.formattedFileSize)
    }

    @Test
    fun testFormattedBitrates() {
        val info = MediaInfo(
            videoBitrate = 5_000_000L,
            audioBitrate = 320_000L,
            audioSampleRate = 48_000L
        )

        assertEquals("5000 kbps", info.formattedVideoBitrate)
        assertEquals("320 kbps", info.formattedAudioBitrate)
        assertEquals("48000 Hz", info.formattedSampleRate)

        val zeroInfo = MediaInfo()
        assertEquals("--", zeroInfo.formattedVideoBitrate)
        assertEquals("--", zeroInfo.formattedAudioBitrate)
        assertEquals("--", zeroInfo.formattedSampleRate)
    }
}
