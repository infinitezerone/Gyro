package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val AUTO_ADVANCE_DELAY_MS = 500L
private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * Single source of truth for the playback session lifecycle, in-flight media load coordination,
 * and session-bounded background monitoring (progress checkpoints and auto-advance).
 *
 * All mutation and monitoring entry points are `internal` to `:core:domain` so external layers
 * only transition session state through domain UseCases.
 */
@Singleton
class PlaybackSessionStateHolder @Inject constructor() {
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val lock = Any()
    private var activeLoadJob: Job? = null
    private var trackingJob: Job? = null
    private var pendingLoadUri: Uri? = null
    private var loadGeneration: Long = 0L

    internal fun setSessionActive(active: Boolean) {
        val jobToCancel: Job?
        val monitorToCancel: Job?
        synchronized(lock) {
            _sessionActive.value = active
            if (!active) {
                loadGeneration++
                pendingLoadUri = null
                jobToCancel = activeLoadJob
                activeLoadJob = null
                monitorToCancel = trackingJob
                trackingJob = null
            } else {
                jobToCancel = null
                monitorToCancel = null
            }
        }
        jobToCancel?.cancel()
        monitorToCancel?.cancel()
    }

    /**
     * Ensures the session-bounded monitor is running while the session is active, automatically
     * persisting pause / 10s heartbeat checkpoints and advancing to the next playlist item on [PlaybackState.ENDED].
     * Cancelled immediately when the session ends.
     */
    internal fun ensureSessionMonitor(
        scope: CoroutineScope,
        engine: PlayerEngine,
        onSaveProgress: () -> Unit,
        onAutoAdvanceNext: () -> Unit,
    ) {
        synchronized(lock) {
            if (!_sessionActive.value || trackingJob?.isActive == true) return
            trackingJob = scope.launch {
                var advanceJob: Job? = null

                // 1. Auto-advance when video ends
                launch {
                    engine.playbackState.collect { state ->
                        if (state == PlaybackState.ENDED && engine.playlist.hasNext) {
                            val endedIndex = engine.playlist.currentIndex.value
                            advanceJob?.cancel()
                            advanceJob = launch {
                                delay(AUTO_ADVANCE_DELAY_MS)
                                if (!_sessionActive.value ||
                                    engine.isPlaying.value ||
                                    engine.playlist.currentIndex.value != endedIndex ||
                                    !engine.playlist.hasNext
                                ) {
                                    return@launch
                                }
                                onAutoAdvanceNext()
                            }
                        }
                    }
                }

                // 2. Persist progress immediately when playback transitions from playing to paused
                launch {
                    var wasPlaying = engine.isPlaying.value
                    engine.isPlaying.collect { playing ->
                        if (wasPlaying && !playing) {
                            onSaveProgress()
                        }
                        wasPlaying = playing
                    }
                }

                // 3. Periodic 10s checkpoint while playing (handles both forward playback and backward seeks)
                launch {
                    engine.isPlaying.collectLatest { playing ->
                        if (!playing) return@collectLatest
                        var lastCheckpointUri: Uri? = null
                        var lastCheckpointPosMs = 0L
                        while (isActive) {
                            delay(CHECKPOINT_INTERVAL_MS)
                            val snapshot = engine.state.value
                            val uri = snapshot.currentUri ?: continue
                            val dur = snapshot.durationMs
                            val pos = engine.timePosMs.value
                            if (dur > 0L && (uri != lastCheckpointUri || abs(pos - lastCheckpointPosMs) >= CHECKPOINT_MIN_DELTA_MS)) {
                                onSaveProgress()
                                lastCheckpointUri = uri
                                lastCheckpointPosMs = pos
                            }
                        }
                    }
                }
            }
        }
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
        val monitorToCancel: Job?
        synchronized(lock) {
            if (!_sessionActive.compareAndSet(expect = true, update = false)) {
                return false
            }
            loadGeneration++
            pendingLoadUri = null
            jobToCancel = activeLoadJob
            activeLoadJob = null
            monitorToCancel = trackingJob
            trackingJob = null
        }
        jobToCancel?.cancel()
        monitorToCancel?.cancel()
        return true
    }
}
