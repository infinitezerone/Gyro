package dev.gyro.engine.audio

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroAudioFocusManagerTest {

    private class TestListener(var playing: Boolean = false) : GyroAudioFocusManager.Listener {
        var playRequestedCount = 0
        var pauseRequestedCount = 0

        override fun onPlayRequested() {
            playRequestedCount++
            playing = true
        }

        override fun onPauseRequested() {
            pauseRequestedCount++
            playing = false
        }

        override val isPlaying: Boolean
            get() = playing
    }

    private val stubContext: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun transientLossWhilePlaying_resumesOnGain() {
        val listener = TestListener(playing = true)
        val manager = GyroAudioFocusManager(context = stubContext, listener = listener, audioManager = null)

        // Incoming call: transient loss
        manager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, listener.pauseRequestedCount)
        assertFalse(listener.playing)

        // Call ends: focus gained
        manager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, listener.playRequestedCount)
        assertTrue(listener.playing)
    }

    @Test
    fun transientLossWhilePaused_staysPausedOnGain() {
        val listener = TestListener(playing = false) // user paused or on list screen
        val manager = GyroAudioFocusManager(context = stubContext, listener = listener, audioManager = null)

        // Incoming call: transient loss while already paused
        manager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0, listener.pauseRequestedCount) // was already paused

        // Call ends: focus gained
        manager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, listener.playRequestedCount) // must NOT auto-play!
        assertFalse(listener.playing)
    }

    @Test
    fun abandonFocus_doesNotPermanentlyBrickManager() {
        val listener = TestListener(playing = false)
        val manager = GyroAudioFocusManager(context = stubContext, listener = listener, audioManager = null)

        // User leaves player screen: abandon focus
        manager.abandon()

        // User starts another video: requestPlay should succeed (not blocked by abandoned state)
        assertTrue(manager.requestPlay())
    }
}
