package dev.gyro.engine

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.state.PlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStoreTest {

    @Test
    fun oneContainer_servesAllProjections() {
        val store = PlaybackStore()
        val volume = store.property({ it.softwareVolume }) { s, v -> s.copy(softwareVolume = v) }
        val title = store.property({ it.mediaTitle }) { s, v -> s.copy(mediaTitle = v) }
        val volumeFlow = volume.asStateFlow()
        val titleFlow = store.select { it.mediaTitle }

        volume.value = 175.0
        title.value = "movie.mkv"

        assertEquals(175.0, store.state.value.softwareVolume, 0.0)
        assertEquals("movie.mkv", store.state.value.mediaTitle)
        assertEquals(175.0, volumeFlow.value, 0.0)
        assertEquals("movie.mkv", titleFlow.value)
        assertSame(store.state.value, store.state.value)
    }

    @Test
    fun controllerWritesLandInTheEnginesOnlyState() {
        val bridge = FakeMpvNativeBridge()
        val engine = GyroPlayerEngine(
            context = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getFilesDir() = kotlin.io.path.createTempDirectory("store_test").toFile()
            },
            bridge = bridge
        )
        engine.ensureInitialized()

        engine.tuning.setSubtitleDelay(2.0)
        engine.video.setBrightness(40)
        engine.geometry.setResizeMode(dev.gyro.engine.model.ResizeMode.CROP)
        engine.tuning.setSubtitleScale(1.5)

        val state = engine.state.value
        assertEquals(2.0, state.subtitleDelaySec, 0.0)
        assertEquals(1.5, state.subtitleScale, 0.0)
        assertEquals(40, state.brightness)
        assertEquals(dev.gyro.engine.model.ResizeMode.CROP, state.resizeMode)
        // All controllers read from the same container, not from private flows.
        assertEquals(state.subtitleDelaySec, engine.tuning.subtitleDelaySec.value, 0.0)
        assertEquals(state.brightness, engine.video.brightness.value)
    }

    @Test
    fun loadPhasesAreVisibleInTheState() {
        val bridge = FakeMpvNativeBridge()
        val engine = GyroPlayerEngine(
            context = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
                override fun getFilesDir() = kotlin.io.path.createTempDirectory("phase_test").toFile()
            },
            bridge = bridge,
            resolver = dev.gyro.core.testing.fakes.FakeMediaResourceResolver()
        )
        engine.ensureInitialized()

        engine.load("file:///tmp/movie.mp4")
        assertTrue(engine.state.value.session is SessionPhase.AwaitingSurface)

        engine.attachSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))
        assertTrue(engine.state.value.session is SessionPhase.Submitting)

        bridge.emitEvent(MpvNativeBridge.Event.FILE_LOADED)
        assertTrue(engine.state.value.session is SessionPhase.Loaded)
        assertEquals(PlaybackState.READY, engine.state.value.playbackState)
    }
}
