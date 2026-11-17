package dev.gyro.core.domain.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the playback session lifecycle state.
 */
@Singleton
class PlaybackSessionStateHolder @Inject constructor() {
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    fun setSessionActive(active: Boolean) {
        _sessionActive.value = active
    }

    fun compareAndSetSessionActive(expect: Boolean, update: Boolean): Boolean {
        return _sessionActive.compareAndSet(expect, update)
    }
}
