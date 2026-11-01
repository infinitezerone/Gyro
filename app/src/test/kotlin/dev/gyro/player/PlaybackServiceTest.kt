package dev.gyro.player

import android.content.Context
import android.content.ContextWrapper
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.GyroPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaybackServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var playlistManager: PlaylistManager

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("playback_service_test")
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

    @Test
    fun testServiceConstants() {
        assertEquals("playback_channel", PlaybackService.CHANNEL_ID)
        assertEquals(1001, PlaybackService.NOTIFICATION_ID)
        assertEquals("dev.gyro.player.ACTION_PLAY", PlaybackService.ACTION_PLAY)
        assertEquals("dev.gyro.player.ACTION_PAUSE", PlaybackService.ACTION_PAUSE)
        assertEquals("dev.gyro.player.ACTION_PLAY_PAUSE", PlaybackService.ACTION_PLAY_PAUSE)
        assertEquals("dev.gyro.player.ACTION_PREV", PlaybackService.ACTION_PREV)
        assertEquals("dev.gyro.player.ACTION_NEXT", PlaybackService.ACTION_NEXT)
        assertEquals("dev.gyro.player.ACTION_STOP", PlaybackService.ACTION_STOP)
    }

    @Test
    fun testActiveEngineAndPlaylistManagerBinding() {
        PlaybackService.activeEngine = engine
        PlaybackService.activePlaylistManager = playlistManager

        assertNotNull(PlaybackService.activeEngine)
        assertEquals(engine, PlaybackService.activeEngine)
        assertNotNull(PlaybackService.activePlaylistManager)
        assertEquals(playlistManager, PlaybackService.activePlaylistManager)

        // Clear after test
        PlaybackService.activeEngine = null
        PlaybackService.activePlaylistManager = null
        assertNull(PlaybackService.activeEngine)
        assertNull(PlaybackService.activePlaylistManager)
    }
}
