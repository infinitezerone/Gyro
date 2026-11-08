package dev.gyro.engine.session

import android.view.KeyEvent

/**
 * Command surface the system media pipeline drives: notification actions, service intents,
 * and headset/media-button keys. A narrow interface so hosts of [MediaSessionManager] (and
 * their JVM unit tests) depend on the routing contract, not the MediaSession implementation.
 */
interface MediaSessionControls {
    fun play()
    fun pause()
    fun togglePlayPause()
    fun skipToNext(): Boolean
    fun skipToPrevious(): Boolean
    fun handleKeyEvent(keyEvent: KeyEvent?): Boolean
}
