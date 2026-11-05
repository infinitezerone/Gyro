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
        assertTrue(fakeBridge.eventListener != null)
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

        val tracks = engine.tracks.value
        assertEquals(2, tracks.size)
        assertEquals(1, tracks[0].id)
        assertEquals("audio", tracks[0].type)
        assertEquals("English", tracks[0].title)
        assertTrue(tracks[0].isSelected)

        assertEquals(2, tracks[1].id)
        assertEquals("sub", tracks[1].type)
        assertFalse(tracks[1].isSelected)

        engine.selectTrack("audio", 1)
        assertEquals("1", fakeBridge.properties["aid"])

        engine.selectTrack("sub", 0)
        assertEquals("no", fakeBridge.properties["sid"])
    }

    @Test
    fun testResizeMode() {
        engine.setResizeMode(ResizeMode.CROP)
        assertEquals(ResizeMode.CROP, engine.resizeMode.value)
        assertEquals(1.0, fakeBridge.properties["panscan"] as Double, 0.001)
        assertEquals("-1", fakeBridge.properties["video-aspect-override"])

        engine.setResizeMode(ResizeMode.ASPECT_16_9)
        assertEquals(ResizeMode.ASPECT_16_9, engine.resizeMode.value)
        assertEquals(0.0, fakeBridge.properties["panscan"] as Double, 0.001)
        assertEquals("16:9", fakeBridge.properties["video-aspect-override"])
    }

    @Test
    fun testSubtitleAndAudioDelay() {
        engine.setSubtitleDelay(1.25)
        assertEquals(1.3, engine.subDelay.value, 0.001)
        assertEquals(1.3, fakeBridge.properties["sub-delay"] as Double, 0.001)

        engine.setAudioDelay(-0.8)
        assertEquals(-0.8, engine.audioDelay.value, 0.001)
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
        assertEquals(null, fakeBridge.eventListener)
        assertEquals(PlaybackState.IDLE, engine.playbackState.value)
        assertFalse(engine.isPlaying.value)
        assertNull(engine.currentUri.value)
        assertEquals(VideoDimensions(), engine.videoDimensions.value)
        assertEquals(0f, engine.videoAspect.value, 0.0001f)
        assertFalse(engine.firstFrameRendered.value)
        assertFalse(engine.geometryReady.value)
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

        assertEquals(VideoDimensions(1080, 1920), engine.videoDimensions.value)
        assertEquals(1080f / 1920f, engine.videoAspect.value, 0.0001f)
        assertTrue(engine.geometryReady.value)
    }

    @Test
    fun testPublishVideoDimensions_gatesOnVideoOutParamsToPreventDualAspectFlash() {
        // Before VO is up, only dwidth/dheight are present; must NOT publish premature shape
        fakeBridge.properties["dwidth"] = 1280L
        fakeBridge.properties["dheight"] = 720L
        fakeBridge.properties["video-params/rotate"] = 270L

        fakeBridge.emitProperty("video-params")

        assertEquals(VideoDimensions(), engine.videoDimensions.value)
        assertFalse(engine.geometryReady.value)

        // Once video-out-params becomes available, it publishes once in final display orientation
        fakeBridge.properties["video-out-params/dw"] = 1920L
        fakeBridge.properties["video-out-params/dh"] = 1080L
        fakeBridge.properties["video-out-params/rotate"] = 0L

        fakeBridge.emitProperty("video-out-params")

        assertEquals(VideoDimensions(1920, 1080), engine.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.videoAspect.value, 0.0001f)
        assertTrue(engine.geometryReady.value)
    }

    @Test
    fun testLoad_keepsPreviousDimensionsBehindShutterUntilNewGeometryArrives() {
        fakeBridge.properties["video-out-params/dw"] = 1920L
        fakeBridge.properties["video-out-params/dh"] = 1080L
        fakeBridge.emitProperty("video-out-params")
        fakeBridge.emitEvent(MpvNativeBridge.Event.FIRST_FRAME)

        assertEquals(VideoDimensions(1920, 1080), engine.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.videoAspect.value, 0.0001f)
        assertTrue(engine.firstFrameRendered.value)
        assertTrue(engine.geometryReady.value)

        engine.load("file:///tmp/another.mp4")

        assertEquals(VideoDimensions(1920, 1080), engine.videoDimensions.value)
        assertEquals(1920f / 1080f, engine.videoAspect.value, 0.0001f)
        assertFalse(engine.firstFrameRendered.value)
        assertFalse(engine.geometryReady.value)
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
    fun testOnDisplayRefreshRateMeasured_setsDisplayFpsOverrideAndDeduplicates() {
        engine.onDisplayRefreshRateMeasured(90.0f)
        assertEquals(90.0, fakeBridge.properties["display-fps-override"] as Double, 0.01)
        assertEquals(90.0, fakeBridge.properties["display-fps"] as Double, 0.01)

        // Sub-0.5Hz jitter is ignored
        engine.onDisplayRefreshRateMeasured(90.2f)
        assertEquals(90.0, fakeBridge.properties["display-fps-override"] as Double, 0.01)

        // Real mode switch (90Hz -> 120Hz) updates mpv
        engine.onDisplayRefreshRateMeasured(120.0f)
        assertEquals(120.0, fakeBridge.properties["display-fps-override"] as Double, 0.01)
        assertEquals(120.0, fakeBridge.properties["display-fps"] as Double, 0.01)
    }

    @Test
    fun testSelectPreferredDisplayMode_matchesMultiplesAndHighRefreshForNonDivisors() {
        val modes = listOf(
            dev.gyro.engine.ui.DisplayModeCandidate(1, 1272, 2772, 120.00001f),
            dev.gyro.engine.ui.DisplayModeCandidate(2, 1272, 2772, 60.000004f),
            dev.gyro.engine.ui.DisplayModeCandidate(3, 1272, 2772, 90.0f),
            dev.gyro.engine.ui.DisplayModeCandidate(4, 1272, 2772, 144.00002f),
            dev.gyro.engine.ui.DisplayModeCandidate(5, 1272, 2772, 165.0f)
        )

        // 24fps (5x = 120Hz) -> 120Hz (id=1)
        assertEquals(1, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 23.976f)?.modeId)
        // 30fps (4x = 120Hz) -> 120Hz (id=1)
        assertEquals(1, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 30.0f)?.modeId)
        // 60fps (2x = 120Hz) -> 120Hz (id=1)
        assertEquals(1, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 60.0f)?.modeId)
        // 90fps (1x = 90Hz) -> 90Hz (id=3)
        assertEquals(3, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 90.0f)?.modeId)
        // 87.36fps transient (within 3% of 90Hz) -> snaps to 90Hz (id=3), avoiding false 120Hz churn
        assertEquals(3, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 87.3584f)?.modeId)
        // 48fps (3x = 144Hz) -> 144Hz (id=4)
        assertEquals(4, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 48.0f)?.modeId)
        // 75.07fps (no integer multiple) -> 120Hz (id=1) for finest standard VSync granularity
        assertEquals(1, dev.gyro.engine.ui.selectPreferredDisplayMode(modes, 1272, 2772, 75.0699f)?.modeId)
    }

    @Test
    fun testNormalizeContentFps_snapsTransientJitterToNominalRates() {
        assertEquals(90.0f, dev.gyro.engine.ui.normalizeContentFps(87.3584f), 0.001f)
        assertEquals(23.976f, dev.gyro.engine.ui.normalizeContentFps(23.976f), 0.001f)
        assertEquals(59.94f, dev.gyro.engine.ui.normalizeContentFps(59.94f), 0.001f)
        assertEquals(75.0699f, dev.gyro.engine.ui.normalizeContentFps(75.0699f), 0.001f)
    }

    @Test
    fun testVideoFrameRate_publishedWhenContainerFpsArrivesAfterFileLoaded() {
        assertTrue(fakeBridge.isPropertyObserved("container-fps", MpvNativeBridge.Format.DOUBLE))
        assertEquals(0f, engine.videoFrameRate.value, 0.001f)

        fakeBridge.emitPropertyDouble("container-fps", 90.0)
        assertEquals(90.0f, engine.videoFrameRate.value, 0.01f)

        // Estimated fps jitter does not overwrite established container frame rate
        fakeBridge.emitPropertyDouble("estimated-vf-fps", 87.3584)
        assertEquals(90.0f, engine.videoFrameRate.value, 0.01f)
    }
}
