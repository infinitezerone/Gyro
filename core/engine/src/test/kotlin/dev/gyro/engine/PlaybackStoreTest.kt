package dev.gyro.engine

import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.state.PlaybackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    fun batch_coalescesMultipleWritesIntoSingleEmissionAndExposesIntermediateReads() {
        val store = PlaybackStore()
        val volume = store.property({ it.softwareVolume }) { s, v -> s.copy(softwareVolume = v) }
        val title = store.property({ it.mediaTitle }) { s, v -> s.copy(mediaTitle = v) }
        val emissions = mutableListOf<PlaybackSnapshot>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { emissions.add(it) }
        }
        try {
            assertEquals(1, emissions.size)
            store.batch {
                volume.value = 150.0
                assertEquals(150.0, volume.value, 0.0)
                title.value = "batched.mkv"
                assertEquals("batched.mkv", title.value)
                // StateFlow has not emitted yet while batch is still open
                assertEquals(1, emissions.size)
            }
            assertEquals(2, emissions.size)
            assertEquals(150.0, emissions.last().softwareVolume, 0.0)
            assertEquals("batched.mkv", emissions.last().mediaTitle)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun diagnosticsTickWithoutInvalidatingPlaybackState() {
        val store = PlaybackStore()
        val stateEmissions = mutableListOf<PlaybackSnapshot>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { stateEmissions.add(it) }
        }
        try {
            store.update { it.copy(isPlaying = true) }
            val stateEmissionCount = stateEmissions.size

            repeat(20) { tick ->
                store.updateDiagnostics { it.copy(droppedFrames = tick.toLong(), fps = 60.0) }
            }

            // Telemetry must not cost a playback-state emission: the screen collects `state`,
            // and a dropped-frame counter moving is not a reason to recompose it.
            assertEquals(stateEmissionCount, stateEmissions.size)
            assertEquals(19L, store.diagnostics.value.droppedFrames)
            assertEquals(60.0, store.diagnostics.value.fps, 0.0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun controllerWritesLandInTheEnginesOnlyState_pureEngineWithoutPlatformContext() {
        val bridge = FakeMpvNativeBridge()
        val engine = GyroPlayerEngine(bridge = bridge)
        engine.warmUp()

        engine.tuning.setSubtitleDelay(2.0)
        engine.video.setBrightness(40)
        engine.geometry.setResizeMode(ResizeMode.CROP)
        engine.tuning.setSubtitleScale(1.5)

        val state = engine.state.value
        assertEquals(2.0, state.subtitleDelaySec, 0.0)
        assertEquals(1.5, state.subtitleScale, 0.0)
        assertEquals(40, state.brightness)
        assertEquals(ResizeMode.CROP, state.resizeMode)
        // All controllers read from the same container, not from private flows.
        assertEquals(state.subtitleDelaySec, engine.tuning.subtitleDelaySec.value, 0.0)
        assertEquals(state.brightness, engine.video.brightness.value)
        engine.release()
    }

    @Test
    fun loadPhasesAreVisibleInTheState() {
        val bridge = FakeMpvNativeBridge()
        val engine = GyroPlayerEngine(
            bridge = bridge,
            resolver = FakeMediaResourceResolver()
        )
        engine.warmUp()

        engine.load("file:///tmp/movie.mp4")
        assertTrue(engine.state.value.session is SessionPhase.AwaitingSurface)

        engine.attachSurface()
        assertTrue(engine.state.value.session is SessionPhase.Submitting)

        bridge.emitEvent(MpvNativeBridge.Event.FILE_LOADED)
        assertTrue(engine.state.value.session is SessionPhase.Loaded)
        assertEquals(PlaybackState.READY, engine.state.value.playbackState)
        engine.release()
    }

    @Test
    fun headlessPlaybackAllowsLoadingWithoutSurface() {
        val bridge = FakeMpvNativeBridge()
        val engine = GyroPlayerEngine(
            bridge = bridge,
            resolver = FakeMediaResourceResolver()
        )
        engine.warmUp()
        engine.isHeadlessMode = true

        engine.load("file:///tmp/audio.mp3")
        assertTrue(engine.state.value.session is SessionPhase.Submitting)

        bridge.emitEvent(MpvNativeBridge.Event.FILE_LOADED)
        assertTrue(engine.state.value.session is SessionPhase.Loaded)
        assertEquals(PlaybackState.READY, engine.state.value.playbackState)
        engine.release()
    }
}
