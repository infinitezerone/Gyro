package dev.gyro.player.service

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals("dev.gyro.player.ACTION_REWIND", PlaybackService.ACTION_REWIND)
        assertEquals("dev.gyro.player.ACTION_FAST_FORWARD", PlaybackService.ACTION_FAST_FORWARD)
        assertEquals("dev.gyro.player.ACTION_STOP", PlaybackService.ACTION_STOP)
        assertEquals("dev.gyro.player.ACTION_DISMISS", PlaybackService.ACTION_DISMISS)
    }
}
