package dev.gyro.core.data.repository

import android.net.TestUri
import dev.gyro.core.model.VideoMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistManagerTest {

    private fun createItem(id: Long, name: String): VideoMediaItem {
        return VideoMediaItem(
            id = id,
            uriString = "content://media/external/video/media/$id",
            displayName = name,
            durationMs = 60_000L,
            sizeBytes = 1000L,
            width = 1920,
            height = 1080,
            dateModified = 1000L,
            folderName = "Anime"
        )
    }

    @Test
    fun testPlaylistNavigation() {
        val manager = PlaylistManager()
        val items = listOf(
            createItem(1, "Episode 01.mkv"),
            createItem(2, "Episode 02.mkv"),
            createItem(3, "Episode 03.mkv")
        )

        manager.setPlaylist(items, 0)
        assertEquals(0, manager.currentIndex.value)
        assertEquals("Episode 01.mkv", manager.currentItem?.displayName)
        assertFalse(manager.hasPrevious)
        assertTrue(manager.hasNext)

        // Advance to Next (Episode 2)
        val next1 = manager.playNext()
        assertEquals("Episode 02.mkv", next1?.displayName)
        assertEquals(1, manager.currentIndex.value)
        assertTrue(manager.hasPrevious)
        assertTrue(manager.hasNext)

        // Advance to Next (Episode 3)
        val next2 = manager.playNext()
        assertEquals("Episode 03.mkv", next2?.displayName)
        assertEquals(2, manager.currentIndex.value)
        assertTrue(manager.hasPrevious)
        assertFalse(manager.hasNext)

        // Overflow next should return null
        assertNull(manager.playNext())
        assertEquals(2, manager.currentIndex.value)

        // Go Back to Previous (Episode 2)
        val prev1 = manager.playPrevious()
        assertEquals("Episode 02.mkv", prev1?.displayName)
        assertEquals(1, manager.currentIndex.value)

        // Jump to index 0 directly
        val jump = manager.playAtIndex(0)
        assertEquals("Episode 01.mkv", jump?.displayName)
        assertEquals(0, manager.currentIndex.value)
    }

    @Test
    fun testSetSingle() {
        val manager = PlaylistManager()
        manager.setSingle(TestUri("content://doc/1"), "Single Movie.mp4")

        assertEquals(1, manager.items.value.size)
        assertEquals(0, manager.currentIndex.value)
        assertEquals("Single Movie.mp4", manager.currentItem?.displayName)
        assertFalse(manager.hasPrevious)
        assertFalse(manager.hasNext)
    }
}
