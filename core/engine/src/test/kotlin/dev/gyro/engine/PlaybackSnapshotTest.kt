package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
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

class PlaybackSnapshotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine

    private fun context(): Context {
        filesDir = tempFolder.newFolder()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
    }

    @Before
    fun setUp() {
        testContext = context()
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.warmUp()
    }

    @Test
    fun snapshotIsAvailableBeforeInitialization() {
        val uninitialized = GyroPlayerEngine(context = context(), bridge = FakeMpvNativeBridge())
        val snapshot = uninitialized.state.value
        assertEquals(PlaybackState.IDLE, snapshot.playbackState)
        assertFalse(snapshot.isPlaying)
        assertEquals(1.0f, snapshot.speed, 0f)
        assertEquals(ResizeMode.FIT, snapshot.resizeMode)
    }

    @Test
    fun commandWritesReachTheSnapshotSynchronously() {
        engine.tuning.setSubtitleDelay(1.3)
        engine.tuning.setSubtitleScale(1.4)
        engine.geometry.setResizeMode(ResizeMode.CROP)
        engine.video.setBrightness(30)

        val snapshot = engine.state.value
        assertEquals(1.3, snapshot.subtitleDelaySec, 0.0)
        assertEquals(1.4, snapshot.subtitleScale, 0.0)
        assertEquals(ResizeMode.CROP, snapshot.resizeMode)
        assertEquals(30, snapshot.brightness)
    }

    @Test
    fun oneSourceChangingKeepsEveryOtherField() {
        engine.tuning.setSubtitleDelay(2.0)
        engine.video.setSaturation(-40)
        engine.audio.setNightMode(true)

        engine.geometry.setResizeMode(ResizeMode.STRETCH)

        val snapshot = engine.state.value
        assertEquals(ResizeMode.STRETCH, snapshot.resizeMode)
        assertEquals(2.0, snapshot.subtitleDelaySec, 0.0)
        assertEquals(-40, snapshot.saturation)
        assertTrue(snapshot.nightMode)
    }

    @Test
    fun playbackLifecycleFieldsMoveTogether() {
        fakeBridge.emitEvent(MpvNativeBridge.Event.FILE_LOADED)
        assertEquals(PlaybackState.READY, engine.state.value.playbackState)

        fakeBridge.emitEvent(MpvNativeBridge.Event.END_FILE)
        val ended = engine.state.value
        assertEquals(PlaybackState.ENDED, ended.playbackState)
        assertFalse(ended.isPlaying)
    }

    @Test
    fun timePosIsNotPartOfTheSnapshot() {
        // Position ticks at report frequency; folding them in would recompose the surface for a
        // value only the scrub bar reads. The API has no such field by construction - this test
        // documents the decision next to the snapshot's other assertions.
        engine.seekTo(30_000L)
        assertTrue(engine.timePosMs.value >= 0L)
        assertEquals(0L, engine.state.value.durationMs)
    }
}
