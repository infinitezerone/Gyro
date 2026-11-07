package dev.gyro.engine.session

import android.content.Context
import android.content.ContextWrapper
import android.media.session.PlaybackState
import android.net.TestUri
import android.view.KeyEvent
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState as EnginePlaybackState
import dev.gyro.engine.media.PlaylistManager
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

    private fun createMediaSessionManager(): MediaSessionManager {
        return MediaSessionManager(
            context = testContext,
            engine = engine,
            playlistManager = playlistManager,
            coroutineScope = testScope,
            uriParser = { str -> TestUri(str) }
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
    }

    @Test
    fun testBuildPlaybackState_whenPaused() {
        val manager = createMediaSessionManager()

        fakeBridge.emitPropertyFlag("pause", true)
        fakeBridge.emitPropertyDouble("time-pos", 10.0)
        fakeBridge.emitPropertyDouble("speed", 1.0)

        val snapshot = manager.getPlaybackStateSnapshot()
        assertEquals(PlaybackState.STATE_PAUSED, snapshot.state)
        assertEquals(10000L, snapshot.positionMs)
    }

    @Test
    fun testBuildPlaybackState_whenBuffering() {
        val manager = createMediaSessionManager()

        fakeBridge.emitPropertyFlag("pause", false)
        fakeBridge.emitPropertyFlag("paused-for-cache", true)

        val snapshot = manager.getPlaybackStateSnapshot()
        assertEquals(PlaybackState.STATE_BUFFERING, snapshot.state)
    }

    @Test
    fun testBuildPlaybackState_whenEnded() {
        val manager = createMediaSessionManager()

        fakeBridge.emitEvent(dev.gyro.engine.MpvNativeBridge.Event.END_FILE)
        testScope.advanceUntilIdle()

        val snapshot = manager.getPlaybackStateSnapshot()
        assertEquals(PlaybackState.STATE_STOPPED, snapshot.state)
    }

    // =========================================================================
    // 2. MediaMetadata Synchronization
    // =========================================================================

    @Test
    fun testBuildMediaMetadata_synchronization() {
        val manager = createMediaSessionManager()

        fakeBridge.emitPropertyString("media-title", "Test Video Title")
        fakeBridge.emitPropertyDouble("duration", 120.0)

        val snapshot = manager.getMediaMetadataSnapshot()
        assertEquals("Test Video Title", snapshot.title)
        assertEquals("Gyro Player", snapshot.artist)
        assertEquals(120000L, snapshot.durationMs)
    }

    // =========================================================================
    // 3. Transport Controls Routing
    // =========================================================================

    @Test
    fun testPlayPauseTransportRouting() {
        val manager = createMediaSessionManager()

        // Play
        manager.play()
        assertEquals(false, fakeBridge.getPropertyBoolean("pause"))

        // Pause
        manager.pause()
        assertEquals(true, fakeBridge.getPropertyBoolean("pause"))

        // Toggle
        manager.togglePlayPause()
        assertEquals(false, fakeBridge.getPropertyBoolean("pause"))

        // Stop
        var stopNotified = false
        manager.onStopRequested = { stopNotified = true }
        manager.stop()
        assertEquals(true, fakeBridge.getPropertyBoolean("pause"))
        assertTrue(stopNotified)

        // Seek
        fakeBridge.emitEvent(dev.gyro.engine.MpvNativeBridge.Event.FILE_LOADED)
        manager.seekTo(45000L)
        assertEquals("seek", fakeBridge.lastCommand()?.firstOrNull())
        assertEquals("45.0", fakeBridge.lastCommand()?.getOrNull(1))
        assertEquals("absolute", fakeBridge.lastCommand()?.getOrNull(2))
    }

    @Test
    fun testPlaylistNavigationViaMediaSession() {
        val manager = createMediaSessionManager()
        val item1 = VideoMediaItem(
            id = 1,
            uriString = "content://media/1",
            displayName = "Ep 1",
            durationMs = 10000,
            sizeBytes = 100,
            width = 1920,
            height = 1080,
            dateModified = 1,
            folderName = "Anime"
        )
        val item2 = VideoMediaItem(
            id = 2,
            uriString = "content://media/2",
            displayName = "Ep 2",
            durationMs = 10000,
            sizeBytes = 100,
            width = 1920,
            height = 1080,
            dateModified = 1,
            folderName = "Anime"
        )
        playlistManager.setPlaylist(listOf(item1, item2), 0)

        // Next
        assertTrue(manager.skipToNext())
        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("content://media/2", engine.currentUri.value?.toString())

        // No more next
        assertFalse(manager.skipToNext())

        // Previous
        assertTrue(manager.skipToPrevious())
        assertEquals(0, playlistManager.currentIndex.value)
        assertEquals("content://media/1", engine.currentUri.value?.toString())

        // No more previous
        assertFalse(manager.skipToPrevious())
    }

    // =========================================================================
    // 4. Headset / Media Button KeyCode Routing
    // =========================================================================

    @Test
    fun testMediaButtonKeyCodeRouting() {
        val manager = createMediaSessionManager()

        // Play/Pause key
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(false, fakeBridge.getPropertyBoolean("pause"))

        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(true, fakeBridge.getPropertyBoolean("pause"))

        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(false, fakeBridge.getPropertyBoolean("pause"))

        // When playing, toggle pauses
        fakeBridge.emitPropertyFlag("pause", false)
        testScope.advanceUntilIdle()
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(true, fakeBridge.getPropertyBoolean("pause"))

        // Stop
        var stopped = false
        manager.onStopRequested = { stopped = true }
        assertTrue(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
        assertTrue(stopped)

        // ACTION_UP should be ignored
        assertFalse(manager.handleKeyCode(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))

        // Unknown key
        assertFalse(manager.handleKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
    }

    // =========================================================================
    // 5. Lifecycle and Engine Flow Observation
    // =========================================================================

    @Test
    fun testEngineObservationTriggersUpdate() {
        var notifiedPlaying: Boolean? = null
        val manager = createMediaSessionManager()
        manager.onPlaybackStateChanged = { notifiedPlaying = it }

        fakeBridge.emitPropertyFlag("pause", false)
        testScope.advanceUntilIdle()
        assertEquals(true, notifiedPlaying)

        fakeBridge.emitPropertyFlag("pause", true)
        testScope.advanceUntilIdle()
        assertEquals(false, notifiedPlaying)
    }

    @Test
    fun testReleaseCancelsScope() {
        val manager = createMediaSessionManager()
        manager.release()
        // Calling methods after release should not crash
        assertNotNull(manager.getPlaybackStateSnapshot())
    }
}
