package dev.gyro.engine.tracks

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.TrackType
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
        manager = TrackAndChapterManager(bridge)
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
        manager.selectTrack(TrackType.AUDIO, 2)
        assertEquals("2", bridge.properties["aid"])

        manager.selectTrack(TrackType.AUDIO, 0)
        assertEquals("no", bridge.properties["aid"])

        manager.selectTrack(TrackType.SUBTITLE, 1)
        assertEquals("1", bridge.properties["sid"])

        manager.selectTrack(TrackType.SUBTITLE, 0)
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

    @Test
    fun testClearTracksAndReset() {
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/id"] = 1L
        bridge.properties["track-list/0/type"] = "audio"
        bridge.properties["track-list/0/title"] = "Stereo"
        manager.refreshTracks()
        assertEquals(1, manager.tracks.value.size)

        manager.clearTracks()
        assertTrue(manager.tracks.value.isEmpty())

        manager.refreshTracks()
        assertEquals(1, manager.tracks.value.size)
        manager.reset()
        assertTrue(manager.tracks.value.isEmpty())
        assertTrue(manager.chapters.value.isEmpty())
    }

    @Test
    fun testEndFile_clearsChaptersAndRetainsTracks() {
        bridge.properties["track-list/count"] = 1L
        bridge.properties["track-list/0/id"] = 1L
        bridge.properties["track-list/0/type"] = "sub"
        bridge.properties["chapter-list/count"] = 1L
        bridge.properties["chapter-list/0/title"] = "Intro"
        manager.refreshTracks()
        manager.refreshChapters()
        assertEquals(1, manager.tracks.value.size)
        assertEquals(1, manager.chapters.value.size)

        manager.onEvent(dev.gyro.engine.MpvEvent.EndFile(dev.gyro.engine.MpvNativeBridge.EndReason.EOF))
        assertEquals(1, manager.tracks.value.size)
        assertTrue(manager.chapters.value.isEmpty())

        manager.reset()
        assertTrue(manager.tracks.value.isEmpty())
        assertTrue(manager.chapters.value.isEmpty())
    }

    // region 次字幕（双语）

    private fun seedTwoSubtitleTracks(primaryId: Long = 1L) {
        bridge.properties["track-list/count"] = 2L
        bridge.properties["track-list/0/id"] = 1L
        bridge.properties["track-list/0/type"] = "sub"
        bridge.properties["track-list/0/title"] = "Chinese"
        bridge.properties["track-list/0/selected"] = primaryId == 1L
        bridge.properties["track-list/1/id"] = 2L
        bridge.properties["track-list/1/type"] = "sub"
        bridge.properties["track-list/1/title"] = "English"
        bridge.properties["track-list/1/selected"] = primaryId == 2L
        manager.refreshTracks()
    }

    @Test
    fun selectSecondarySubtitle_setsIdAndMirrorsState() {
        seedTwoSubtitleTracks()

        manager.selectSecondarySubtitle(2)

        assertEquals("2", bridge.properties["secondary-sid"])
        assertEquals(2, manager.secondarySubId.value)

        manager.selectSecondarySubtitle(0)

        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun selectSecondarySubtitle_rejectsPrimaryTrack() {
        seedTwoSubtitleTracks()

        manager.selectSecondarySubtitle(1)

        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun selectSecondarySubtitle_rejectsWhenPrimarySubtitleIsOff() {
        seedTwoSubtitleTracks(primaryId = 0L)

        manager.selectSecondarySubtitle(2)

        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun selectPrimarySubtitleOff_clearsSecondary() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        manager.selectTrack(TrackType.SUBTITLE, 0)

        assertEquals("no", bridge.properties["sid"])
        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun refreshTracks_clearsSecondaryWhenPrimarySubtitleIsOff() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        bridge.properties["track-list/0/selected"] = false
        bridge.properties["track-list/1/selected"] = false
        manager.refreshTracks()

        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun selectPrimarySubtitle_movesSecondaryOffTheSameTrack() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        manager.selectTrack(TrackType.SUBTITLE, 2)

        assertEquals("2", bridge.properties["sid"])
        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun refreshTracks_clearsSecondaryThatBecamePrimaryOnNextFile() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        // 新文件里同一条轨道号变成了主字幕（mpv 会复用 track id）。
        bridge.properties["track-list/0/selected"] = false
        bridge.properties["track-list/1/selected"] = true
        manager.refreshTracks()

        assertEquals("no", bridge.properties["secondary-sid"])
        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun secondarySidPropertyWithoutValue_isTreatedAsOff() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        // "no" 等非数值值会以 MPV_FORMAT_NONE（无数据）回调到 PropertyChanged。
        manager.onEvent(dev.gyro.engine.MpvEvent.PropertyChanged("secondary-sid"))

        assertEquals(0, manager.secondarySubId.value)
    }

    @Test
    fun reset_clearsSecondarySubtitleState() {
        seedTwoSubtitleTracks()
        manager.selectSecondarySubtitle(2)

        manager.reset()

        assertEquals(0, manager.secondarySubId.value)
    }

    // endregion
}
