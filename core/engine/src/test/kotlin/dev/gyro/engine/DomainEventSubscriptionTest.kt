package dev.gyro.engine

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.audio.AudioEffectsController
import dev.gyro.engine.video.VideoEffectsController
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.api.session.PlaybackSessionState
import dev.gyro.engine.video.VideoGeometryCoordinator
import dev.gyro.engine.tracks.TrackAndChapterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventSubscriptionTest {

    @Test
    fun audioHandlesEndAndShutdownWithoutEngineAndCanResubscribe() = runTest {
        val bridge = RecordingBridge()
        val fake = bridge.fake
        val audio = AudioEffectsController(bridge)
        audio.setNightMode(true)
        audio.setStereoDownmix(true)
        audio.setSoftwareVolume(180.0)
        fake.emitEndFile()
        assertEquals(180.0, audio.softwareVolume.value, 0.0)

        val scope = eventScope()
        val subscription = audio.observeEvents(scope)
        assertEquals(180.0, audio.softwareVolume.value, 0.0)
        fake.emitFileLoaded()
        assertEquals(180.0, audio.softwareVolume.value, 0.0)

        for (event in listOf(MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN)) {
            audio.setSoftwareVolume(180.0)
            fake.emitEvent(event)
            assertEquals(100.0, audio.softwareVolume.value, 0.0)
            assertEquals(100.0, fake.properties["volume"])
            assertTrue(audio.nightMode.value)
            assertTrue(audio.stereoDownmix.value)
            assertEquals(
                "${AudioEffectsController.FILTER_STEREO_DOWNMIX},${AudioEffectsController.FILTER_DYNAUDNORM}",
                fake.properties["af"]
            )
        }

        scope.cancel()
        assertTrue(subscription.isCancelled)
        bridge.delivered.clear()
        audio.setSoftwareVolume(160.0)
        fake.emitEndFile()
        fake.emitShutdown()
        assertEquals(160.0, audio.softwareVolume.value, 0.0)
        assertTrue(bridge.delivered.isEmpty())

        audio.observeEvents(eventScope())
        assertTrue(bridge.delivered.isEmpty())
        assertEquals(160.0, audio.softwareVolume.value, 0.0)
        fake.emitEndFile()
        assertEquals(100.0, audio.softwareVolume.value, 0.0)
        assertEquals(listOf(MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE)), bridge.delivered)
    }

    @Test
    fun videoHandlesTypedPropertiesWithoutEngineAndCanResubscribe() = runTest {
        val bridge = RecordingBridge()
        val fake = bridge.fake
        val video = VideoEffectsController(bridge)
        fake.emitPropertyFlag("deband", true)
        assertFalse(video.deband.value)

        val scope = eventScope()
        val subscription = video.observeEvents(scope)
        assertFalse(video.deband.value)
        fake.emitPropertyFlag("deband", true)
        fake.emitPropertyLong("brightness", 25L)
        fake.emitPropertyLong("contrast", 150L)
        fake.emitPropertyLong("saturation", -150L)
        assertTrue(video.deband.value)
        assertEquals(25, video.brightness.value)
        assertEquals(100, video.contrast.value)
        assertEquals(-100, video.saturation.value)

        fake.emitPropertyFlag("pause", false)
        fake.emitPropertyLong("chapter", 2L)
        fake.emitPropertyDouble("brightness", 90.0)
        fake.emitPropertyString("deband", "no")
        fake.emitEndFile()
        assertTrue(video.deband.value)
        assertEquals(25, video.brightness.value)
        assertEquals(100, video.contrast.value)
        assertEquals(-100, video.saturation.value)

        scope.cancel()
        assertTrue(subscription.isCancelled)
        bridge.delivered.clear()
        fake.emitPropertyFlag("deband", false)
        fake.emitPropertyLong("brightness", 5L)
        fake.emitPropertyLong("contrast", 5L)
        fake.emitPropertyLong("saturation", 5L)
        assertTrue(video.deband.value)
        assertEquals(25, video.brightness.value)
        assertEquals(100, video.contrast.value)
        assertEquals(-100, video.saturation.value)
        assertTrue(bridge.delivered.isEmpty())

        video.observeEvents(eventScope())
        assertTrue(bridge.delivered.isEmpty())
        assertEquals(25, video.brightness.value)
        fake.emitPropertyLong("brightness", -40L)
        assertEquals(-40, video.brightness.value)
        assertEquals(listOf(MpvEvent.LongProperty("brightness", -40L)), bridge.delivered)
    }

    @Test
    fun tracksAndChaptersHandleEventsWithoutEngineAndCanResubscribe() = runTest {
        val bridge = RecordingBridge()
        val fake = bridge.fake
        val manager = TrackAndChapterManager(bridge)
        fake.properties["track-list/count"] = 1L
        fake.properties["track-list/0/id"] = 7L
        fake.properties["track-list/0/type"] = "audio"
        fake.properties["track-list/0/title"] = "Original"
        fake.properties["chapter-list/count"] = 1L
        fake.properties["chapter-list/0/title"] = "Intro"
        fake.properties["chapter-list/0/time"] = 1.5
        fake.properties["chapter"] = 0L
        fake.emitFileLoaded()
        assertTrue(manager.tracks.value.isEmpty())
        assertTrue(manager.chapters.value.isEmpty())

        val scope = eventScope()
        val subscription = manager.observeEvents(scope)
        assertTrue(manager.tracks.value.isEmpty())
        fake.emitFileLoaded()
        assertEquals(7, manager.tracks.value.single().id)
        assertEquals("Original", manager.tracks.value.single().title)
        assertEquals("Intro", manager.chapters.value.single().title)
        assertEquals(1500L, manager.chapters.value.single().timeMs)
        assertEquals(0, manager.currentChapter.value)

        fake.properties["track-list/0/title"] = "Changed"
        fake.properties["chapter-list/0/title"] = "Updated chapter"
        fake.emitTracksChanged()
        assertEquals("Changed", manager.tracks.value.single().title)
        assertEquals("Intro", manager.chapters.value.single().title)
        fake.emitProperty("chapter-list")
        assertEquals("Updated chapter", manager.chapters.value.single().title)
        fake.properties["track-list/0/title"] = "Property update"
        fake.emitProperty("track-list")
        assertEquals("Property update", manager.tracks.value.single().title)
        fake.emitPropertyLong("chapter", 2L)
        assertEquals(2, manager.currentChapter.value)
        fake.emitPropertyLong("brightness", 40L)
        assertEquals(2, manager.currentChapter.value)

        for (event in listOf(MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN)) {
            fake.emitFileLoaded()
            assertFalse(manager.chapters.value.isEmpty())
            fake.emitEvent(event)
            assertTrue(manager.chapters.value.isEmpty())
            assertEquals(-1, manager.currentChapter.value)
            assertEquals("Property update", manager.tracks.value.single().title)
        }

        fake.emitFileLoaded()
        val chapters = manager.chapters.value
        val currentChapter = manager.currentChapter.value
        scope.cancel()
        assertTrue(subscription.isCancelled)
        bridge.delivered.clear()
        fake.properties["track-list/0/title"] = "After cancellation"
        fake.properties["chapter-list/0/title"] = "After cancellation"
        fake.emitFileLoaded()
        fake.emitTracksChanged()
        fake.emitProperty("track-list")
        fake.emitProperty("chapter-list")
        fake.emitPropertyLong("chapter", 9L)
        fake.emitEndFile()
        fake.emitShutdown()
        assertEquals("Property update", manager.tracks.value.single().title)
        assertEquals(chapters, manager.chapters.value)
        assertEquals(currentChapter, manager.currentChapter.value)
        assertTrue(bridge.delivered.isEmpty())

        manager.observeEvents(eventScope())
        assertTrue(bridge.delivered.isEmpty())
        assertEquals(chapters, manager.chapters.value)
        fake.emitFileLoaded()
        assertEquals("After cancellation", manager.tracks.value.single().title)
        assertEquals("After cancellation", manager.chapters.value.single().title)
        assertEquals(9, manager.currentChapter.value)
        assertEquals(listOf(MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED)), bridge.delivered)
    }

    @Test
    fun geometryHandlesLifecycleWithoutEngineAndCanResubscribe() = runTest {
        val bridge = RecordingBridge()
        val fake = bridge.fake
        val geometry = VideoGeometryCoordinator(bridge)
        fake.properties["video"] = "1"
        fake.properties["track-list/count"] = 1L
        fake.properties["track-list/0/type"] = "video"
        fake.properties["track-list/0/demux-w"] = 720L
        fake.properties["track-list/0/demux-h"] = 1280L
        fake.properties["container-fps"] = 25.0
        geometry.onSwitchStarted("test://portrait.mp4", 3000L)
        fake.emitFileLoaded()
        assertEquals(PlaybackSessionState.Switching("test://portrait.mp4", 3000L), geometry.sessionState.value)

        val scope = eventScope()
        val subscription = geometry.observeEvents(scope)
        assertTrue(geometry.sessionState.value is PlaybackSessionState.Switching)
        fake.emitFileLoaded()
        val loaded = geometry.sessionState.value as PlaybackSessionState.Demuxed
        assertEquals("test://portrait.mp4", loaded.url)
        assertEquals(VideoDimensions(720, 1280), loaded.dimensions)
        assertTrue(geometry.geometryReady.value)
        assertFalse(geometry.firstFrameRendered.value)
        assertEquals(25f, geometry.videoFrameRate.value, 0f)
        fake.emitEvent(MpvNativeBridge.Event.FIRST_FRAME)
        assertTrue(geometry.firstFrameRendered.value)
        assertEquals("test://portrait.mp4", (geometry.sessionState.value as PlaybackSessionState.Presenting).url)

        fake.emitEndFile()
        assertEquals(PlaybackSessionState.Ended, geometry.sessionState.value)
        assertFalse(geometry.firstFrameRendered.value)
        assertFalse(geometry.geometryReady.value)
        assertEquals(0f, geometry.videoFrameRate.value, 0f)

        geometry.onSwitchStarted("test://audio.mp3")
        fake.properties["video"] = "no"
        fake.emitFileLoaded()
        assertEquals(
            PlaybackSessionState.Presenting("test://audio.mp3", VideoDimensions(0, 0), 1f),
            geometry.sessionState.value
        )
        assertTrue(geometry.firstFrameRendered.value)
        assertTrue(geometry.geometryReady.value)
        fake.emitShutdown()
        assertEquals(PlaybackSessionState.Ended, geometry.sessionState.value)
        assertFalse(geometry.firstFrameRendered.value)
        assertFalse(geometry.geometryReady.value)

        geometry.reset()
        fake.emitFileLoaded()
        assertEquals("", (geometry.sessionState.value as PlaybackSessionState.Presenting).url)
        geometry.onSwitchStarted("test://next.mp4")
        fake.properties["video"] = "1"
        scope.cancel()
        assertTrue(subscription.isCancelled)
        bridge.delivered.clear()
        fake.emitFileLoaded()
        fake.emitEvent(MpvNativeBridge.Event.FIRST_FRAME)
        fake.emitPropertyDouble("container-fps", 60.0)
        fake.emitEndFile()
        fake.emitShutdown()
        assertEquals(PlaybackSessionState.Switching("test://next.mp4"), geometry.sessionState.value)
        assertFalse(geometry.firstFrameRendered.value)
        assertFalse(geometry.geometryReady.value)
        assertEquals(0f, geometry.videoFrameRate.value, 0f)
        assertTrue(bridge.delivered.isEmpty())

        geometry.observeEvents(eventScope())
        assertTrue(bridge.delivered.isEmpty())
        assertTrue(geometry.sessionState.value is PlaybackSessionState.Switching)
        fake.emitFileLoaded()
        assertEquals("test://next.mp4", (geometry.sessionState.value as PlaybackSessionState.Demuxed).url)
        assertEquals(listOf(MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED)), bridge.delivered)
    }

    @Test
    fun geometryHandlesVoPropertiesAndNormalizesEstimatedFps() = runTest {
        val fake = FakeMpvNativeBridge()
        val geometry = VideoGeometryCoordinator(fake)
        val scope = eventScope()
        geometry.observeEvents(scope)
        geometry.onSwitchStarted("test://adaptive.m3u8")
        fake.properties["video-out-params/dw"] = 640L
        fake.properties["video-out-params/dh"] = 360L
        fake.emitProperty("video-params")
        assertEquals(VideoDimensions(0, 0), geometry.videoDimensions.value)
        assertFalse(geometry.geometryReady.value)

        fake.emitFileLoaded()
        fake.emitEvent(MpvNativeBridge.Event.FIRST_FRAME)
        for ((index, name) in listOf("video-params", "video-out-params").withIndex()) {
            val width = 800L + index * 100L
            fake.properties["video-out-params/dw"] = width
            fake.emitProperty(name)
            assertEquals(VideoDimensions(width.toInt(), 360), geometry.videoDimensions.value)
        }
        for ((index, name) in listOf("width", "height", "dwidth", "dheight").withIndex()) {
            val width = 1000L + index * 100L
            fake.properties["video-out-params/dw"] = width
            fake.emitPropertyLong(name, width)
            assertEquals(VideoDimensions(width.toInt(), 360), geometry.videoDimensions.value)
        }
        fake.properties["video-out-params/dw"] = 1920L
        fake.emitProperty("unrelated")
        fake.emitPropertyLong("chapter", 0L)
        assertEquals(VideoDimensions(1300, 360), geometry.videoDimensions.value)

        fake.emitPropertyDouble("estimated-vf-fps", 23.8)
        assertEquals(23.976f, geometry.videoFrameRate.value, 0.001f)
        fake.emitPropertyDouble("container-fps", 29.8)
        assertEquals(29.8f, geometry.videoFrameRate.value, 0.001f)
        fake.emitPropertyDouble("estimated-vf-fps", 59.8)
        fake.emitPropertyDouble("container-fps", 0.5)
        fake.emitPropertyDouble("container-fps", 1000.0)
        fake.emitPropertyDouble("speed", 2.0)
        assertEquals(29.8f, geometry.videoFrameRate.value, 0.001f)

        scope.cancel()
        fake.emitProperty("video-params")
        fake.emitProperty("video-out-params")
        for (name in listOf("width", "height", "dwidth", "dheight")) {
            fake.emitPropertyLong(name, 1920L)
        }
        fake.emitPropertyDouble("container-fps", 60.0)
        fake.emitPropertyDouble("estimated-vf-fps", 23.8)
        assertEquals(VideoDimensions(1300, 360), geometry.videoDimensions.value)
        assertEquals(29.8f, geometry.videoFrameRate.value, 0.001f)
    }

    private fun TestScope.eventScope(): CoroutineScope = CoroutineScope(
        backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]) + Dispatchers.Unconfined
    )

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class RecordingBridge(val fake: FakeMpvNativeBridge = FakeMpvNativeBridge()) : MpvNativeBridge by fake {
        val delivered = mutableListOf<MpvEvent>()

        override val events: SharedFlow<MpvEvent> = object : SharedFlow<MpvEvent> by fake.events {
            override suspend fun collect(collector: FlowCollector<MpvEvent>): Nothing {
                fake.events.collect(object : FlowCollector<MpvEvent> {
                    override suspend fun emit(value: MpvEvent) {
                        delivered.add(value)
                        collector.emit(value)
                    }
                })
            }
        }
    }
}
