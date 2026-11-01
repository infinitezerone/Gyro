package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun testRelease() {
        engine.release()
        assertEquals(1, fakeBridge.destroyCount)
        assertEquals(null, fakeBridge.eventListener)
        assertEquals(PlaybackState.IDLE, engine.playbackState.value)
        assertFalse(engine.isPlaying.value)
    }
}
