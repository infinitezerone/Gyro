package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the playback session lifecycle and in-flight media load coordination.
 *
 * All mutation and load-coordination entry points are `internal` to `:core:domain` so external
 * layers can only transition session state through domain UseCases.
 */
@Singleton
class PlaybackSessionStateHolder @Inject constructor() {
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val lock = Any()
    private var activeLoadJob: Job? = null
    private var pendingLoadUri: Uri? = null
    private var loadGeneration: Long = 0L

    internal fun setSessionActive(active: Boolean) {
        val jobToCancel: Job?
        synchronized(lock) {
            _sessionActive.value = active
            if (!active) {
                loadGeneration++
                pendingLoadUri = null
                jobToCancel = activeLoadJob
                activeLoadJob = null
            } else {
                jobToCancel = null
            }
        }
        jobToCancel?.cancel()
    }

    /**
     * Marks the session active, cancels any previous in-flight load, and launches [block]
     * bound to a monotonically increasing generation token so stale completions cannot overwrite
     * newer loads or resurrect a terminated session.
     */
    internal fun launchLoad(
        uri: Uri,
        scope: CoroutineScope,
        block: suspend (isCurrentLoad: () -> Boolean) -> Unit,
    ): Job {
        val previousJob: Job?
        val newJob: Job
        synchronized(lock) {
            _sessionActive.value = true
            previousJob = activeLoadJob
            val token = ++loadGeneration
            pendingLoadUri = uri
            val isCurrent: () -> Boolean = {
                synchronized(lock) { _sessionActive.value && loadGeneration == token }
            }
            newJob = scope.launch {
                try {
                    block(isCurrent)
                } finally {
                    synchronized(lock) {
                        if (loadGeneration == token) {
                            pendingLoadUri = null
                            if (activeLoadJob === this.coroutineContext[Job]) {
                                activeLoadJob = null
                            }
                        }
                    }
                }
            }
            activeLoadJob = newJob
        }
        previousJob?.cancel()
        return newJob
    }

    /**
     * Returns true when [uri] is either currently being loaded in an active session, or is
     * already loaded in the active engine session (including [PlaybackState.PAUSED]).
     */
    internal fun isSessionHandlingUri(
        uri: Uri,
        currentEngineUri: Uri?,
        playbackState: PlaybackState,
    ): Boolean {
        synchronized(lock) {
            if (!_sessionActive.value) return false
            if (pendingLoadUri == uri && activeLoadJob?.isActive == true) return true
        }
        return currentEngineUri == uri &&
            playbackState != PlaybackState.IDLE &&
            playbackState != PlaybackState.ERROR
    }

    /**
     * Atomically transitions the session from active to inactive and cancels any in-flight load.
     * Returns true if the session transitioned from active to inactive; false if already inactive.
     */
    internal fun endSession(): Boolean {
        val jobToCancel: Job?
        synchronized(lock) {
            if (!_sessionActive.compareAndSet(expect = true, update = false)) {
                return false
            }
            loadGeneration++
            pendingLoadUri = null
            jobToCancel = activeLoadJob
            activeLoadJob = null
        }
        jobToCancel?.cancel()
        return true
    }
}
