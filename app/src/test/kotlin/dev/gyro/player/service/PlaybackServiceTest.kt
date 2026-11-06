package dev.gyro.player.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The former `testActiveEngineAndPlaylistManagerBinding` asserted that the two `@Volatile`
 * companion fields could be written and cleared. That was a tautology — it verified the nullability
 * of a global, not any behaviour of the service. Both fields are gone: the engine and playlist
 * manager are injected by Hilt now, so the service has no state of its own to assert on.
 */
class PlaybackServiceTest {

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
}
