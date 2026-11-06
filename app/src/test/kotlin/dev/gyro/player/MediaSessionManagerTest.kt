package dev.gyro.player

import android.content.Context
import android.content.ContextWrapper
import android.media.session.PlaybackState
import android.net.TestUri
import android.net.Uri
import android.view.KeyEvent
import dev.gyro.core.data.repository.AndroidUriParser
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSessionManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var playlistManager: PlaylistManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        AndroidUriParser.parser = { str -> TestUri(str) }
        val filesDir = tempFolder.newFolder("mediasession_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getPackageName(): String = "dev.gyro.player"
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()
        playlistManager = PlaylistManager()
    }

    @After
    fun tearDown() {
        AndroidUriParser.parser = { str -> Uri.parse(str) }
    }

    private fun createMediaSessionManager(): MediaSessionManager {
        return MediaSessionManager(
            context = testContext,
            engine = engine,
            playlistManager = playlistManager,
            coroutineScope = testScope
        )
    }

    // =========================================================================
    // 1. PlaybackState Synchronization
    // =========================================================================

    @Test
    fun testBuildPlaybackState_whenPlaying() {
        val manager = createMediaSessionManager()

        // Set engine properties
        fakeBridge.emitPropertyFlag("pause", false)
        fakeBridge.emitPropertyDouble("time-pos", 25.0)
        fakeBridge.emitPropertyDouble("speed", 1.25)

        val snapshot = manager.getPlaybackStateSnapshot()
        assertEquals(PlaybackState.STATE_PLAYING, snapshot.state)
        assertEquals(25000L, snapshot.positionMs)
        assertEquals(1.25f, snapshot.speed, 0.001f)

        // Verify supported action flags
        val actions = snapshot.actions
        assertTrue((actions and PlaybackState.ACTION_PLAY) != 0L)
        assertTrue((actions and PlaybackState.ACTION_PAUSE) != 0L)
        assertTrue((actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L)
        assertTrue((actions and PlaybackState.ACTION_STOP) != 0L)
        assertTrue((actions and PlaybackState.ACTION_SEEK_TO) != 0L)
        assertTrue((actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L)
        assertTrue((actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L)

        manager.release()
    }

    @Test
    fun testBuildPlaybackState_whenPaused() {
        val manager = createMediaSessionManager()

        fakeBridge.emitPropertyFlag("pause", true)
        fakeBridge.emitPropertyDouble("time-pos", 10.0)

        val snapshot = manager.getPlaybackStateSnapshot()
        assertEquals(PlaybackState.STATE_PAUSED, snapshot.state)
        assertEquals(10000L, snapshot.positionMs)

        manager.release()
    }

    // =========================================================================
    // 2. MediaMetadata Synchronization
    // =========================================================================

    @Test
    fun testBuildMediaMetadata() {
        val manager = createMediaSessionManager()

        fakeBridge.emitPropertyString("media-title", "Test Movie 4K")
        fakeBridge.emitPropertyDouble("duration", 120.5)

        val snapshot = manager.getMediaMetadataSnapshot()
        assertEquals("Test Movie 4K", snapshot.title)
        assertEquals("Gyro Player", snapshot.artist)
        assertEquals(120500L, snapshot.durationMs)

        manager.release()
    }

    // =========================================================================
    // 3. Transport Controls Routing
    // =========================================================================

    @Test
    fun testTransportControls_playPauseToggleSeek() {
        val manager = createMediaSessionManager()

        // 1. Play
        manager.play()
        assertEquals(false, fakeBridge.properties["pause"])

        // 2. Pause
        manager.pause()
        assertEquals(true, fakeBridge.properties["pause"])

        // 3. Toggle Play/Pause: when currently paused (pause=true), toggle sets pause=false
        fakeBridge.emitPropertyFlag("pause", true)
        manager.togglePlayPause()
        assertEquals(false, fakeBridge.properties["pause"])

        // 4. Seek To
        engine.attachSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))
        engine.load("http://example.com/stream.mp4")
        fakeBridge.eventListener?.onEvent(MpvNativeBridge.Event.FILE_LOADED)

        manager.seekTo(45000L)
        val lastCommand = fakeBridge.commands.lastOrNull()
        assertEquals("seek", lastCommand?.get(0))
        assertEquals("45.0", lastCommand?.get(1))
        assertEquals("absolute", lastCommand?.get(2))

        manager.release()
    }

    // =========================================================================
    // 4. Playlist Navigation (Skip to Next / Previous)
    // =========================================================================

    @Test
    fun testPlaylistNavigation_nextAndPrevious() {
        val manager = createMediaSessionManager()
        engine.attachSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))

        val item1 = VideoMediaItem(1L, "file:///video1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///video2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")
        val item3 = VideoMediaItem(3L, "file:///video3.mp4", "Video 3", 3000L, 300L, 0, 0, 0L, "F")
        playlistManager.setPlaylist(listOf(item1, item2, item3), 0)

        // Skip to Next -> item2
        assertTrue(manager.skipToNext())
        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("Video 2", playlistManager.currentItem?.displayName)

        // Skip to Next -> item3
        assertTrue(manager.skipToNext())
        assertEquals(2, playlistManager.currentIndex.value)
        assertEquals("Video 3", playlistManager.currentItem?.displayName)

        // Skip to Next at end of playlist -> false
        assertFalse(manager.skipToNext())
        assertEquals(2, playlistManager.currentIndex.value)

        // Skip to Previous -> item2
        assertTrue(manager.skipToPrevious())
        assertEquals(1, playlistManager.currentIndex.value)

        // Skip to Previous -> item1
        assertTrue(manager.skipToPrevious())
        assertEquals(0, playlistManager.currentIndex.value)

        // Skip to Previous at beginning -> false
        assertFalse(manager.skipToPrevious())
        assertEquals(0, playlistManager.currentIndex.value)

        manager.release()
    }

    // =========================================================================
    // 5. Headset & Bluetooth Key Event Handling
    // =========================================================================

    @Test
    fun testHeadsetKeyEvents() {
        val manager = createMediaSessionManager()

        // 1. KEYCODE_MEDIA_PLAY
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(false, fakeBridge.properties["pause"])

        // 2. KEYCODE_MEDIA_PAUSE
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(true, fakeBridge.properties["pause"])

        // 3. KEYCODE_MEDIA_PLAY_PAUSE
        fakeBridge.emitPropertyFlag("pause", true)
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(false, fakeBridge.properties["pause"])

        // 4. KEYCODE_HEADSETHOOK (Earphone inline button)
        fakeBridge.emitPropertyFlag("pause", false) // Currently playing
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(true, fakeBridge.properties["pause"]) // Toggles to paused

        // 5. KEYCODE_MEDIA_NEXT
        val item1 = VideoMediaItem(1L, "file:///video1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///video2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")
        playlistManager.setPlaylist(listOf(item1, item2), 0)

        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(1, playlistManager.currentIndex.value)

        // 6. KEYCODE_MEDIA_PREVIOUS
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(0, playlistManager.currentIndex.value)

        // 7. KEYCODE_MEDIA_STOP
        var stopCallbackCalled = false
        manager.onStopRequested = { stopCallbackCalled = true }
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
        assertTrue(stopCallbackCalled)
        assertEquals(true, fakeBridge.properties["pause"])

        // 8. Key Up should be ignored
        assertFalse(manager.handleKeyCode(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))

        // 9. Unhandled keys should return false
        assertFalse(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))

        // 10. Null event should return false
        assertFalse(manager.handleKeyEvent(null))

        manager.release()
    }

    // =========================================================================
    // 6. Coroutine Observation & Callback Flow
    // =========================================================================

    @Test
    fun testObservationFlow_triggersOnPlaybackStateChanged() {
        val manager = createMediaSessionManager()
        var lastReportedPlaying: Boolean? = null
        manager.onPlaybackStateChanged = { playing ->
            lastReportedPlaying = playing
        }

        // Change engine playing state via onPropertyFlag
        fakeBridge.emitPropertyFlag("pause", false)
        testScope.advanceUntilIdle()

        assertEquals(true, lastReportedPlaying)

        fakeBridge.emitPropertyFlag("pause", true)
        testScope.advanceUntilIdle()

        assertEquals(false, lastReportedPlaying)

        manager.release()
    }
}
