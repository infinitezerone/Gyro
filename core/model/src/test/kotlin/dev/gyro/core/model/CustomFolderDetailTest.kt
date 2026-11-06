package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomFolderDetailTest {

    @Test
    fun testUriAccessorMatchesUriString() {
        val folder = CustomFolderDetail(
            uriString = "content://com.android.externalstorage.documents/tree/primary%3AAnime",
            displayName = "Anime",
            videoCount = 10,
            totalSizeBytes = 1024L
        )
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AAnime", folder.uri)
    }

    @Test
    fun testFormattedTotalSize() {
        fun makeFolder(sizeBytes: Long) = CustomFolderDetail(
            uriString = "test://dir",
            displayName = "Test",
            videoCount = 1,
            totalSizeBytes = sizeBytes
        )

        assertEquals("0 B", makeFolder(0L).formattedTotalSize)
        assertEquals("0 B", makeFolder(-100L).formattedTotalSize)
        assertEquals("512 KB", makeFolder(512 * 1024L).formattedTotalSize)
        assertEquals("50.0 MB", makeFolder(50 * 1024 * 1024L).formattedTotalSize)
        assertEquals("1.5 GB", makeFolder((1.5 * 1024 * 1024 * 1024).toLong()).formattedTotalSize)
    }
}
