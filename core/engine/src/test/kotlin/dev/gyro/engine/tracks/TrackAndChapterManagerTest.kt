package dev.gyro.engine.tracks

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackAndChapterManagerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var manager: TrackAndChapterManager

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        manager = RealTrackAndChapterManager(bridge)
    }

    @Test
    fun testInitialState() {
        assertTrue(manager.tracks.value.isEmpty())
        assertTrue(manager.chapters.value.isEmpty())
        assertEquals(-1, manager.currentChapter.value)
    }

    @Test
    fun testChapterParsing_multipleChapters() {
        bridge.properties["chapter-list/count"] = 3L
        bridge.properties["chapter-list/0/title"] = "Prologue"
        bridge.properties["chapter-list/0/time"] = 0.0
        bridge.properties["chapter-list/1/title"] = "The Journey"
        bridge.properties["chapter-list/1/time"] = 125.5
        bridge.properties["chapter-list/2/title"] = "Credits"
        bridge.properties["chapter-list/2/time"] = 600.0
        bridge.properties["chapter"] = 0L

        manager.refreshChapters()

        val chapters = manager.chapters.value
        assertEquals(3, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals(0L, chapters[0].timeMs)
        assertEquals("The Journey", chapters[1].title)
        assertEquals(125500L, chapters[1].timeMs)
        assertEquals("Credits", chapters[2].title)
        assertEquals(600000L, chapters[2].timeMs)
        assertEquals(0, manager.currentChapter.value)
    }

    @Test
    fun testTrackSelection() {
        manager.selectTrack("audio", 2)
        assertEquals("2", bridge.properties["aid"])

        manager.selectTrack("audio", 0)
        assertEquals("no", bridge.properties["aid"])

        manager.selectTrack("sub", 1)
        assertEquals("1", bridge.properties["sid"])

        manager.selectTrack("sub", 0)
        assertEquals("no", bridge.properties["sid"])
    }

    @Test
    fun testAddSubtitle() {
        manager.addSubtitle("/sdcard/sub.srt")
        assertTrue(bridge.hasCommand("sub-add", "/sdcard/sub.srt", "select"))
    }

    @Test
    fun testClearChapters() {
        bridge.properties["chapter-list/count"] = 1L
        bridge.properties["chapter-list/0/title"] = "Intro"
        bridge.properties["chapter-list/0/time"] = 0.0
        manager.refreshChapters()
        assertEquals(1, manager.chapters.value.size)

        manager.clearChapters()
        assertTrue(manager.chapters.value.isEmpty())
        assertEquals(-1, manager.currentChapter.value)
    }
}
