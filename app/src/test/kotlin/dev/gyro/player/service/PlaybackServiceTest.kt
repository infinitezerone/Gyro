package dev.gyro.player.service

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.Surface
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.util.MainDispatcherRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.session.MediaSessionControls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Drives [PlaybackService.handleCommand] directly on the JVM: dispatch routing, headless-mode
 * arming/disarming, and the STOP/DISMISS sticky semantics. The Android Service plumbing
 * (notification, MediaSession construction) stays out of scope; a recording fake stands in for
 * [MediaSessionControls] and the real engine + [FakeMpvNativeBridge] verify what reaches mpv.
 */
class PlaybackServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // PlaybackService's field initializers touch Dispatchers.Main (serviceScope).
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var service: PlaybackService
    private lateinit var controls: RecordingControls

    private var notificationStopCalled = false
    private var stopAndResetCalled = false

    private class RecordingControls : MediaSessionControls {
        val calls = mutableListOf<String>()
        val keyEvents = mutableListOf<KeyEvent?>()

        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun togglePlayPause() { calls += "toggle" }
        override fun skipToNext(): Boolean { calls += "next"; return true }
        override fun skipToPrevious(): Boolean { calls += "prev"; return true }
        override fun handleKeyEvent(keyEvent: KeyEvent?): Boolean { keyEvents += keyEvent; return true }
    }

    @Before
    fun setUp() {
        val filesDir: File = tempFolder.newFolder()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(
            context = context,
            bridge = fakeBridge,
            resolver = DefaultMediaResourceResolver(context)
        )
        engine.warmUp()
        service = PlaybackService()
        service.engine = engine
        controls = RecordingControls()
        notificationStopCalled = false
        stopAndResetCalled = false
    }

    private fun dispatch(action: String?, keyEvent: KeyEvent? = null): Int =
        service.handleCommand(
            action = action,
            keyEvent = keyEvent,
            controls = controls,
            onNotificationStop = { notificationStopCalled = true },
            stopAndReset = {
                stopAndResetCalled = true
                // Real teardown path so the headless disarm contract is exercised, not assumed.
                service.stopForegroundAndSelf()
            }
        )

    @Test
    fun unknownActionArmsHeadlessAndReturnsSticky() {
        assertEquals(Service.START_STICKY, dispatch(null))

        // A service start implies background playback intent: loads must bypass Surface queueing.
        assertTrue(engine.isHeadlessMode)
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun transportActionsRouteToSessionControls() {
        val routing = mapOf(
            PlaybackService.ACTION_PLAY to "play",
            PlaybackService.ACTION_PAUSE to "pause",
            PlaybackService.ACTION_PLAY_PAUSE to "toggle",
            PlaybackService.ACTION_PREV to "prev",
            PlaybackService.ACTION_NEXT to "next",
        )
        for ((action, expected) in routing) {
            controls.calls.clear()
            assertEquals("sticky for $action", Service.START_STICKY, dispatch(action))
            assertEquals("routing for $action", listOf(expected), controls.calls)
            assertTrue("headless armed for $action", engine.isHeadlessMode)
        }
    }

    @Test
    fun rewindAndFastForwardIssueRelativeSeekCommands() {
        // seekRelative only fires once the file is loaded; drive the real load path headless.
        engine.isHeadlessMode = true
        engine.load("file:///tmp/clip.mp4")
        fakeBridge.emitFileLoaded()
        assertTrue(fakeBridge.findCommands("loadfile").isNotEmpty())

        assertEquals(Service.START_STICKY, dispatch(PlaybackService.ACTION_REWIND))
        assertEquals(listOf("seek", "-10.0", "relative"), fakeBridge.findCommands("seek").single())

        assertEquals(Service.START_STICKY, dispatch(PlaybackService.ACTION_FAST_FORWARD))
        assertEquals(listOf("seek", "10.0", "relative"), fakeBridge.findCommands("seek").last())
    }

    @Test
    fun stopNotifiesSessionAndDisarmsHeadless() {
        assertEquals(Service.START_NOT_STICKY, dispatch(PlaybackService.ACTION_STOP))

        assertTrue("stop must notify session to end", notificationStopCalled)
        assertTrue(stopAndResetCalled)
        assertFalse("teardown must disarm headless mode", engine.isHeadlessMode)
    }

    @Test
    fun dismissNotifiesSessionAndDisarmsHeadless() {
        assertEquals(Service.START_NOT_STICKY, dispatch(PlaybackService.ACTION_DISMISS))

        assertTrue("dismiss must notify session to end", notificationStopCalled)
        assertTrue(stopAndResetCalled)
        assertFalse(engine.isHeadlessMode)
    }

    @Test
    fun actionDoesNotArmHeadlessIfSurfaceIsAttached() {
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            engine.attachSurface(surface)
            assertTrue(engine.isSurfaceAttached)

            assertEquals(Service.START_STICKY, dispatch(PlaybackService.ACTION_PLAY))
            assertFalse("must not arm headless if surface is attached", engine.isHeadlessMode)
        } finally {
            engine.detachSurface()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun mediaButtonRoutesKeyEventToControls() {
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

        assertEquals(Service.START_STICKY, dispatch(Intent.ACTION_MEDIA_BUTTON, keyEvent))

        assertSame(keyEvent, controls.keyEvents.single())
    }

    @Test
    fun mediaButtonWithoutKeyEventDispatchesNothing() {
        assertEquals(Service.START_STICKY, dispatch(Intent.ACTION_MEDIA_BUTTON, keyEvent = null))

        assertTrue(controls.keyEvents.isEmpty())
        assertTrue(controls.calls.isEmpty())
    }
}
