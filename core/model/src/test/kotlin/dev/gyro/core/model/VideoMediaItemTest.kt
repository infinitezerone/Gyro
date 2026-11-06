package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoMediaItemTest {

    @Test
    fun testUriAccessorMatchesUriString() {
        val item = VideoMediaItem(
            id = 10L,
            uriString = "content://media/external/video/media/10",
            displayName = "test.mp4",
            durationMs = 60_000L,
            sizeBytes = 1024L,
            width = 1920,
            height = 1080,
            dateModified = 1000L,
            folderName = "Movies"
        )
        assertEquals("content://media/external/video/media/10", item.uri)
    }

    @Test
    fun testFormattedDuration() {
        fun makeItem(durationMs: Long) = VideoMediaItem(
            id = 1L,
            uriString = "test://1",
            displayName = "test",
            durationMs = durationMs,
            sizeBytes = 0L,
            width = 0,
            height = 0,
            dateModified = 0L,
            folderName = ""
        )

        assertEquals("00:00", makeItem(0L).formattedDuration)
        assertEquals("00:00", makeItem(-500L).formattedDuration)
        assertEquals("05:24", makeItem((5 * 60 + 24) * 1000L).formattedDuration)
        assertEquals("1:00:05", makeItem((3600 + 5) * 1000L).formattedDuration)
        assertEquals("2:34:56", makeItem((2 * 3600 + 34 * 60 + 56) * 1000L).formattedDuration)
    }

    @Test
    fun testFormattedSize() {
        fun makeItem(sizeBytes: Long) = VideoMediaItem(
            id = 1L,
            uriString = "test://1",
            displayName = "test",
            durationMs = 0L,
            sizeBytes = sizeBytes,
            width = 0,
            height = 0,
            dateModified = 0L,
            folderName = ""
        )

        assertEquals("--", makeItem(0L).formattedSize)
        assertEquals("--", makeItem(-100L).formattedSize)
        assertEquals("500 KB", makeItem(500 * 1024L).formattedSize)
        assertEquals("125.5 MB", makeItem((125.5 * 1024 * 1024).toLong()).formattedSize)
        assertEquals("2.4 GB", makeItem((2.4 * 1024 * 1024 * 1024).toLong()).formattedSize)
    }

    @Test
    fun testResolutionBadge() {
        fun makeItem(width: Int, height: Int) = VideoMediaItem(
            id = 1L,
            uriString = "test://1",
            displayName = "test",
            durationMs = 0L,
            sizeBytes = 0L,
            width = width,
            height = height,
            dateModified = 0L,
            folderName = ""
        )

        assertEquals("4K", makeItem(3840, 2160).resolutionBadge)
        assertEquals("4K", makeItem(4096, 2160).resolutionBadge)
        assertEquals("2K", makeItem(2560, 1440).resolutionBadge)
        assertEquals("1080P", makeItem(1920, 1080).resolutionBadge)
        assertEquals("720P", makeItem(1280, 720).resolutionBadge)
        assertEquals("480P", makeItem(640, 480).resolutionBadge)
        assertNull(makeItem(0, 0).resolutionBadge)
        assertNull(makeItem(-1, -1).resolutionBadge)
    }
}
