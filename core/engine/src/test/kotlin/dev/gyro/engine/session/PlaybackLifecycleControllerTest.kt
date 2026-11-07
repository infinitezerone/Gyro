package dev.gyro.engine.session

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.audio.AudioFocusController
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.state.PlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackLifecycleControllerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var store: PlaybackStore
    private lateinit var controller: PlaybackLifecycleController
    private var audioFocusGranted = true
    private var abandonCalled = false
    private var releaseCalled = false

    private val fakeAudioFocus = object : AudioFocusController {
        override fun requestPlay(): Boolean = audioFocusGranted
        override fun notifyUserPaused() {}
        override fun abandon() { abandonCalled = true }
        override fun reset() {}
        override fun release() { releaseCalled = true }
    }

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        store = PlaybackStore()
        controller = PlaybackLifecycleController(
            bridge = bridge,
            store = store,
            audioFocusManager = fakeAudioFocus,
            getTimePosMs = { 1000L },
            getRequestedTitle = { "" }
        )
    }

    @Test
    fun play_whenAudioFocusGranted_setsPauseFalse() {
        audioFocusGranted = true
        controller.play()
        assertEquals(false, bridge.properties["pause"])
    }

    @Test
    fun play_whenAudioFocusDenied_suppressesPlayback() {
        audioFocusGranted = false
        controller.play()
        assertFalse(bridge.properties.containsKey("pause"))
    }

    @Test
    fun pause_setsPauseTrue() {
        controller.pause()
        assertEquals(true, bridge.properties["pause"])
    }

    @Test
    fun togglePlayPause_switchesStateCorrectly() {
        store.update { it.copy(isPlaying = true) }
        controller.togglePlayPause()
        assertEquals(true, bridge.properties["pause"])

        store.update { it.copy(isPlaying = false) }
        controller.togglePlayPause()
        assertEquals(false, bridge.properties["pause"])
    }

    @Test
    fun setSpeed_clampsAndDispatches() {
        controller.setSpeed(1.5f)
        assertEquals(1.5, bridge.properties["speed"] as Double, 0.001)

        controller.setSpeed(10.0f)
        assertEquals(4.0, bridge.properties["speed"] as Double, 0.001)

        controller.setSpeed(0.1f)
        assertEquals(0.25, bridge.properties["speed"] as Double, 0.001)
    }

    @Test
    fun toggleHwdec_togglesBetweenHardwareAndSoftware() {
        store.update { it.copy(hwdecCurrent = "mediacodec-copy") }
        controller.toggleHwdec()
        assertEquals("no", bridge.properties["hwdec"])
        assertEquals("no", store.current.hwdecCurrent)

        controller.toggleHwdec()
        val expected = if (bridge.isZeroCopySafe()) "mediacodec,mediacodec-copy" else "mediacodec-copy"
        assertEquals(expected, bridge.properties["hwdec"])
    }

    @Test
    fun onEvent_lifecycleAndProperties_updateStore() {
        controller.onEvent(MpvEvent.FlagProperty("pause", false))
        assertTrue(store.current.isPlaying)
        assertEquals(PlaybackState.READY, store.current.playbackState)

        controller.onEvent(MpvEvent.FlagProperty("pause", true))
        assertFalse(store.current.isPlaying)

        controller.onEvent(MpvEvent.FlagProperty("paused-for-cache", true))
        assertEquals(PlaybackState.BUFFERING, store.current.playbackState)

        controller.onEvent(MpvEvent.DoubleProperty("speed", 1.75))
        assertEquals(1.75f, store.current.speed, 0.001f)

        controller.onEvent(MpvEvent.LongProperty("frame-drop-count", 42L))
        assertEquals(42L, store.current.droppedFrames)

        controller.onEvent(MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE))
        assertEquals(PlaybackState.ENDED, store.current.playbackState)
        assertFalse(store.current.isPlaying)

        controller.abandonAudioFocus()
        assertTrue(abandonCalled)

        controller.release()
        assertTrue(releaseCalled)
    }
}
