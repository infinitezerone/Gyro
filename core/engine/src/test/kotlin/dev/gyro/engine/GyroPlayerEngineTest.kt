package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.view.Surface
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GyroPlayerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("engine_core_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()
    }

    @Test
    fun testEnsureInitialized_callsCreateAndInit() {
        assertEquals(1, fakeBridge.createCount)
        assertEquals(1, fakeBridge.initCount)
        assertTrue(fakeBridge.eventSubscriberCount > 0)
    }

    @Test
    fun testPlayPauseToggle() {
        engine.play()
        assertEquals(false, fakeBridge.properties["pause"])

        engine.pause()
        assertEquals(true, fakeBridge.properties["pause"])

        // Simulate property emission from native
        fakeBridge.emitPropertyFlag("pause", false)
        assertTrue(engine.isPlaying.value)

        engine.togglePlayPause()
        assertEquals(true, fakeBridge.properties["pause"])
    }

    @Test
    fun testSeekToAndSeekRelative() {
        fakeBridge.emitFileLoaded()

        engine.seekTo(15000L)
        val lastCmd = fakeBridge.lastCommand()
        assertEquals(listOf("seek", "15.0", "absolute"), lastCmd)

        engine.seekRelative(-5000L)
        val relativeCmd = fakeBridge.lastCommand()
        assertEquals(listOf("seek", "-5.0", "relative"), relativeCmd)
    }

    @Test
    fun testSetSpeed() {
        engine.setSpeed(1.75f)
        assertEquals(1.75, fakeBridge.properties["speed"] as Double, 0.001)

        // Clamping check
        engine.setSpeed(10.0f)
        assertEquals(4.0, fakeBridge.properties["speed"] as Double, 0.001)

        engine.setSpeed(0.1f)
        assertEquals(0.25, fakeBridge.properties["speed"] as Double, 0.001)
    }

    @Test
    fun testTracksRefreshAndSelect() {
        fakeBridge.properties["track-list/count"] = 2L
        fakeBridge.properties["track-list/0/id"] = 1L
        fakeBridge.properties["track-list/0/type"] = "audio"
        fakeBridge.properties["track-list/0/title"] = "English"
        fakeBridge.properties["track-list/0/selected"] = true

        fakeBridge.properties["track-list/1/id"] = 2L
        fakeBridge.properties["track-list/1/type"] = "sub"
        fakeBridge.properties["track-list/1/title"] = "English SDH"
        fakeBridge.properties["track-list/1/selected"] = false

        fakeBridge.emitProperty("track-list")

        val tracks = engine.trackManager.tracks.value
        assertEquals(2, tracks.size)
        assertEquals(1, tracks[0].id)
        assertEquals("audio", tracks[0].type)
        assertEquals("English", tracks[0].title)
        assertTrue(tracks[0].isSelected)

        assertEquals(2, tracks[1].id)
        assertEquals("sub", tracks[1].type)
        assertFalse(tracks[1].isSelected)

        engine.trackManager.selectTrack("audio", 1)
        assertEquals("1", fakeBridge.properties["aid"])

        engine.trackManager.selectTrack("sub", 0)
        assertEquals("no", fakeBridge.properties["sid"])
    }

    @Test
    fun testResizeMode() {
        engine.geometry.setResizeMode(ResizeMode.CROP)
        assertEquals(ResizeMode.CROP, engine.geometry.resizeMode.value)
        assertEquals(1.0, fakeBridge.properties["panscan"] as Double, 0.001)
        assertEquals("-1", fakeBridge.properties["video-aspect-override"])

        engine.geometry.setResizeMode(ResizeMode.ASPECT_16_9)
        assertEquals(ResizeMode.ASPECT_16_9, engine.geometry.resizeMode.value)
        assertEquals(0.0, fakeBridge.properties["panscan"] as Double, 0.001)
        assertEquals("16:9", fakeBridge.properties["video-aspect-override"])
    }

    @Test
    fun testSubtitleAndAudioDelay() {
        engine.tuning.setSubtitleDelay(1.25)
        assertEquals(1.3, engine.tuning.subtitleDelaySec.value, 0.001)
        assertEquals(1.3, fakeBridge.properties["sub-delay"] as Double, 0.001)

        engine.tuning.setAudioDelay(-0.8)
        assertEquals(-0.8, engine.tuning.audioDelaySec.value, 0.001)
        assertEquals(-0.8, fakeBridge.properties["audio-delay"] as Double, 0.001)
    }

    @Test
    fun testSeekTo_publishesTheTargetPositionImmediately() {
        fakeBridge.emitFileLoaded()

        engine.seekTo(5000L)

        assertEquals(5000L, engine.timePosMs.value)
        assertTrue(fakeBridge.hasCommand("seek", "5.0", "absolute"))
    }

    @Test
    fun testSeekTo_ignoresStaleTimePosReportsUntilTheSeekLands() {
        fakeBridge.emitFileLoaded()

        engine.seekTo(5000L)
        // mpv keeps reporting the old spot until the seek completes; publishing those makes every
        // position consumer jump back for a beat.
        fakeBridge.emitPropertyDouble("time-pos", 2.0)
        assertEquals(5000L, engine.timePosMs.value)

        // A report at the target: the seek landed.
        fakeBridge.emitPropertyDouble("time-pos", 5.1)
        assertEquals(5100L, engine.timePosMs.value)
    }

    @Test
    fun testSeekRelative_targetsTheDisplayedPosition() {
        fakeBridge.emitFileLoaded()
        engine.seekTo(5000L)

        engine.seekRelative(-3000L)

        assertEquals(2000L, engine.timePosMs.value)
    }

    @Test
    fun testLoad_clearsAnyPendingSeek() {
        fakeBridge.emitFileLoaded()
        engine.seekTo(5000L)
        assertEquals(5000L, engine.timePosMs.value)

        engine.load("file:///tmp/next.mp4")
        fakeBridge.emitPropertyDouble("time-pos", 2.0)

        assertEquals(2000L, engine.timePosMs.value)
    }

    @Test
    fun testTimelineProperties_updatePublicFlowsAndMediaInfo() {
        fakeBridge.emitPropertyDouble("time-pos", 12.5)
        fakeBridge.emitPropertyDouble("duration", 180.25)
        fakeBridge.emitPropertyDouble("demuxer-cache-time", 30.0)

        assertEquals(12500L, engine.timePosMs.value)
        assertEquals(180250L, engine.durationMs.value)
        assertEquals(30000L, engine.bufferedPosMs.value)
        assertEquals(180250L, engine.queryMediaInfo().durationMs)

        fakeBridge.emitPropertyDouble("demuxer-cache-time", 2.0)
        assertEquals(12500L, engine.bufferedPosMs.value)

        engine.load("file:///tmp/next.mp4", startPositionMs = 4000L)
        assertEquals(4000L, engine.timePosMs.value)
        assertEquals(0L, engine.durationMs.value)
    }

    @Test
    fun testSeekWhileLoading_executesOnceAfterFileLoaded() {
        engine.attachSurface(Surface(android.graphics.SurfaceTexture(0)))
        engine.load("file:///tmp/video.mp4")
        assertFalse(engine.isFileLoaded)

        engine.seekTo(15000L)
        engine.seekTo(25000L)
        assertFalse(fakeBridge.commands.any { it.first() == "seek" })

        fakeBridge.emitFileLoaded()
        assertTrue(engine.isFileLoaded)
        assertEquals(25000L, engine.timePosMs.value)
        assertEquals(listOf(listOf("seek", "25.0", "absolute")),
            fakeBridge.commands.filter { it.first() == "seek" })

        fakeBridge.emitFileLoaded()
        assertEquals(1, fakeBridge.commands.count { it.first() == "seek" })
    }

    @Test
    fun testNewLoad_discardsPreviousDeferredSeekAndUsesResumeOption() {
        engine.seekTo(90000L)
        engine.attachSurface(Surface(android.graphics.SurfaceTexture(0)))
        engine.load("file:///tmp/next.mp4", startPositionMs = 12000L)
        fakeBridge.emitFileLoaded()

        assertEquals(12000L, engine.timePosMs.value)
        assertFalse(fakeBridge.commands.any { it.first() == "seek" })
        val loadCommand = fakeBridge.commands.single { it.first() == "loadfile" }
        assertEquals(listOf("loadfile", "file:///tmp/next.mp4", "replace", "0"), loadCommand.take(4))
        assertEquals("start=+${"%.3f".format(12.0)}", loadCommand[4])
    }

    @Test
    fun testChapterSeek_updatesDisplayedPositionAndFiltersStaleReports() {
        fakeBridge.properties["chapter-list/count"] = 2L
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.properties["chapter-list/1/time"] = 60.0
        fakeBridge.emitFileLoaded()

        engine.seekToChapter(1)
        assertEquals(60000L, engine.timePosMs.value)
        assertEquals(1, engine.trackManager.currentChapter.value)
        assertEquals(1L, fakeBridge.properties["chapter"])

        fakeBridge.emitPropertyDouble("time-pos", 10.0)
        assertEquals(60000L, engine.timePosMs.value)
        fakeBridge.emitPropertyDouble("time-pos", 60.1)
        assertEquals(60100L, engine.timePosMs.value)
    }

    @Test
    fun testRedraw_onlyDispatchesWhenSurfaceAttached() {
        assertEquals(0, fakeBridge.redrawCount)
        // 未 attach surface 时调用 redraw 不触发
        engine.redraw()
        assertEquals(0, fakeBridge.redrawCount)

        val surface = Surface(android.graphics.SurfaceTexture(0))
        engine.attachSurface(surface)
        engine.redraw()
        assertEquals(1, fakeBridge.redrawCount)

        engine.detachSurface()
        engine.redraw()
        assertEquals(1, fakeBridge.redrawCount)
    }

    @Test
    fun testRelease() {
        val uri = TestUri("content://media/external/video/media/99")
        engine.loadUri(uri)
        assertEquals(uri, engine.currentUri.value)

        engine.release()
        assertEquals(1, fakeBridge.destroyCount)
        assertEquals(0, fakeBridge.eventSubscriberCount)
        assertEquals(PlaybackState.IDLE, engine.playbackState.value)
        assertFalse(engine.isPlaying.value)
        assertNull(engine.currentUri.value)
        assertEquals(VideoDimensions(), engine.geometry.videoDimensions.value)
        assertEquals(0f, engine.geometry.videoAspect.value, 0.0001f)
        assertFalse(engine.geometry.firstFrameRendered.value)
        assertFalse(engine.geometry.geometryReady.value)
    }

    @Test
    fun testEventSubscriptions_areCancelledAndRecreatedOnce() {
        val subscribers = fakeBridge.eventSubscriberCount
        assertTrue(subscribers > 1)
        engine.ensureInitialized()
        assertEquals(subscribers, fakeBridge.eventSubscriberCount)
        fakeBridge.emitPropertyDouble("speed", 1.5)
        assertEquals(1.5f, engine.speed.value, 0.001f)

        engine.release()
        assertEquals(0, fakeBridge.eventSubscriberCount)
        fakeBridge.emitPropertyDouble("speed", 3.0)
        assertEquals(1.5f, engine.speed.value, 0.001f)

        engine.ensureInitialized()
        assertEquals(subscribers, fakeBridge.eventSubscriberCount)
        fakeBridge.emitPropertyDouble("speed", 2.0)
        assertEquals(2.0f, engine.speed.value, 0.001f)
        engine.release()
        assertEquals(0, fakeBridge.eventSubscriberCount)
    }

    @Test
    fun testFailedInitialization_cancelsEventSubscriptionsBeforeRetry() {
        val failingBridge = FakeMpvNativeBridge()
        var attempts = 0
        val bridge = object : MpvNativeBridge by failingBridge {
            override fun init() {
                attempts += 1
                if (attempts == 1) error("init failed")
                failingBridge.init()
            }
        }
        val retryEngine = GyroPlayerEngine(testContext, bridge)
        retryEngine.ensureInitialized()
        assertEquals(0, failingBridge.eventSubscriberCount)

        retryEngine.ensureInitialized()
        assertEquals(fakeBridge.eventSubscriberCount, failingBridge.eventSubscriberCount)
        failingBridge.emitFileLoaded()
        assertEquals(PlaybackState.READY, retryEngine.playbackState.value)
        retryEngine.release()
        assertEquals(0, failingBridge.eventSubscriberCount)
    }

    @Test
    fun testLoadUri_usesCallerSuppliedDisplayTitle() {
        engine.loadUri(TestUri("content://media/external/video/media/123"), displayTitle = "Named Movie.mkv")
        assertEquals("Named Movie.mkv", engine.mediaTitle.value)
        engine.release()
    }

    @Test
    fun testEnsureInitialized_observesVideoOutParams() {
        assertTrue(fakeBridge.isPropertyObserved("video-out-params", MpvNativeBridge.Format.NONE))
        assertTrue(fakeBridge.isPropertyObserved("video-params", MpvNativeBridge.Format.NONE))
    }

    @Test
    fun testPublishVideoDimensions_fromVideoOutParamsWithRotation() {
        fakeBridge.properties["video-out-params/dw"] = 1920L
        fakeBridge.properties["video-out-params/dh"] = 1080L
        fakeBridge.properties["video-out-params/rotate"] = 90L

        fakeBridge.emitProperty("video-out-params")

        assertEquals(VideoDimensions(1080, 1920), engine.geometry.videoDimensions.value)
        assertEquals(1080f / 1920f, engine.geometry.videoAspect.value, 0.0001f)
        assertTrue(engine.geometry.geometryReady.value)
    }

    @Test
    fun testPublishVideoDimensions_gatesOnVideoOutParamsToPreventDualAspectFlash() {
        // Before VO is up, only dwidth/dheight are present; must NOT publish premature shape
        fakeBridge.properties["dwidth"] = 1280L
        fakeBridge.properties["dheight"] = 720L
        fakeBridge.properties["video-params/rotate"] = 270L

        fakeBridge.emitProperty("video-params")

        assertEquals(VideoDimensions(), engine.geometry.videoDimensions.value)
        assertFalse(engine.geometry.geometryReady.value)

        // Once video-out-params becomes available, it publishes once in final display orientation
        fakeBridge.properties["video-out-params/dw"] = 1920L
        fakeBridge.properties["video-out-params/dh"] = 1080L
        fakeBridge.properties["video-out-params/rotate"] = 0L

        fakeBridge.emitProperty("video-out-params")

        assertEquals(VideoDimensions(1920, 1080), engine.geometry.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.geometry.videoAspect.value, 0.0001f)
        assertTrue(engine.geometry.geometryReady.value)
    }

    @Test
    fun testLoad_keepsPreviousDimensionsBehindShutterUntilNewGeometryArrives() {
        fakeBridge.properties["video-out-params/dw"] = 1920L
        fakeBridge.properties["video-out-params/dh"] = 1080L
        fakeBridge.emitProperty("video-out-params")
        fakeBridge.emitEvent(MpvNativeBridge.Event.FIRST_FRAME)

        assertEquals(VideoDimensions(1920, 1080), engine.geometry.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.geometry.videoAspect.value, 0.0001f)
        assertTrue(engine.geometry.firstFrameRendered.value)
        assertTrue(engine.geometry.geometryReady.value)

        engine.load("file:///tmp/another.mp4")

        assertEquals(VideoDimensions(1920, 1080), engine.geometry.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.geometry.videoAspect.value, 0.0001f)
        assertFalse(engine.geometry.firstFrameRendered.value)
        assertFalse(engine.geometry.geometryReady.value)
    }

    @Test
    fun testDetachSurface_ownershipGuardIgnoresWrongSurface() {
        val surface1 = Surface(android.graphics.SurfaceTexture(0))
        val surface2 = Surface(android.graphics.SurfaceTexture(1))

        engine.attachSurface(surface1)
        assertTrue(engine.isSurfaceAttached)
        assertEquals(1, fakeBridge.attachSurfaceCount)

        // Detaching a different surface must be ignored
        engine.detachSurface(surface2)
        assertTrue(engine.isSurfaceAttached)
        assertEquals(0, fakeBridge.detachSurfaceCount)

        // Detaching the actual attached surface succeeds
        engine.detachSurface(surface1)
        assertFalse(engine.isSurfaceAttached)
        assertEquals(1, fakeBridge.detachSurfaceCount)
    }

    @Test
    fun testLoadUri_updatesCurrentUriImmediately() {
        assertNull(engine.currentUri.value)
        val uri1 = TestUri("content://media/external/video/media/101")
        val uri2 = TestUri("content://media/external/video/media/102")

        engine.loadUri(uri1)
        assertEquals(uri1, engine.currentUri.value)

        engine.loadUri(uri2)
        assertEquals(uri2, engine.currentUri.value)
    }

    @Test
    fun testComputeDisplayFittedBufferSize_fitsDisplayResolutionAcrossAllVideoSizes() {
        // Low-res 360p (16:9) and 4K (16:9) both map to the exact same 2261x1272 native screen FBO
        val from360p = dev.gyro.engine.ui.computeDisplayFittedBufferSize(640, 360, 1272, 2772)
        val from4k = dev.gyro.engine.ui.computeDisplayFittedBufferSize(3840, 2160, 1272, 2772)
        assertEquals(Pair(2261, 1272), from360p)
        assertEquals(from360p, from4k)

        // Portrait 9:16 maps to 1272x2261
        val portrait = dev.gyro.engine.ui.computeDisplayFittedBufferSize(1080, 1920, 1272, 2772)
        assertEquals(Pair(1272, 2261), portrait)

        // Ultrawide 21:9 (2560x1080) fits long edge 2772
        val ultrawide = dev.gyro.engine.ui.computeDisplayFittedBufferSize(2560, 1080, 1272, 2772)
        assertEquals(Pair(2772, 1169), ultrawide)
    }

    @Test
    fun testNormalizeContentFps_snapsTransientJitterToNominalRates() {
        assertEquals(90.0f, dev.gyro.engine.ui.normalizeContentFps(87.3584f), 0.001f)
        assertEquals(23.976f, dev.gyro.engine.ui.normalizeContentFps(23.976f), 0.001f)
        assertEquals(59.94f, dev.gyro.engine.ui.normalizeContentFps(59.94f), 0.001f)
        assertEquals(75.0f, dev.gyro.engine.ui.normalizeContentFps(75.0699f), 0.001f)
        assertEquals(0f, dev.gyro.engine.ui.normalizeContentFps(0.5f), 0.001f)
        assertEquals(80.0f, dev.gyro.engine.ui.normalizeContentFps(80.0f), 0.001f)
    }

    @Test
    fun testUpdateDisplayRefreshRate_setsPropertiesOnBridge() {
        engine.geometry.updateDisplayRefreshRate(120.0f)
        assertEquals(120.0f, engine.geometry.displayRefreshRate.value, 0.001f)
        assertEquals(120.0, fakeBridge.properties["display-fps-override"] as Double, 0.001)
        assertEquals(120.0, fakeBridge.properties["display-fps"] as Double, 0.001)
    }

    @Test
    fun testVideoFrameRate_publishedWhenContainerFpsArrivesAfterFileLoaded() {
        assertTrue(fakeBridge.isPropertyObserved("container-fps", MpvNativeBridge.Format.DOUBLE))
        assertEquals(0f, engine.geometry.videoFrameRate.value, 0.001f)

        fakeBridge.emitPropertyDouble("container-fps", 90.0)
        assertEquals(90.0f, engine.geometry.videoFrameRate.value, 0.01f)
    }
}
