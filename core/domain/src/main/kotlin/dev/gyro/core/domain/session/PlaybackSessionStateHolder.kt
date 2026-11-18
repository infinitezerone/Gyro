package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.model.HwdecMode
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.model.EnginePlaybackException
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackSourceOpenException
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val AUTO_ADVANCE_DELAY_MS = 500L
private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * Single source of truth for the application-scoped playback session lifecycle:
 * - In-flight media load coordination and monotonic generation cancellation
 * - Session-bounded background monitoring (10s / pause checkpoints and `ENDED` auto-advance)
 * - Hardware-decode auto-fallback state machine and failure classification
 * - Session-scoped sleep timer countdown (survives UI/Activity lifecycle changes)
 *
 * All mutation and monitoring entry points are `internal` to `:core:domain` so external layers
 * only transition session state through domain UseCases or [PlaybackSession].
 */
@Singleton
class PlaybackSessionStateHolder @Inject constructor() {
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackSessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PlaybackSessionEvent> = _events.asSharedFlow()

    private val _failureReason = MutableStateFlow<PlaybackFailureReason?>(null)
    val failureReason: StateFlow<PlaybackFailureReason?> = _failureReason.asStateFlow()

    private val _sleepTimerSecondsRemaining = MutableStateFlow(0L)
    val sleepTimerSecondsRemaining: StateFlow<Long> = _sleepTimerSecondsRemaining.asStateFlow()

    private val lock = Any()
    private var activeLoadJob: Job? = null
    private var trackingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var pendingLoadUri: Uri? = null
    private var loadGeneration: Long = 0L

    private var lastHandledError: Throwable? = null
    private var hwdecFallbackAttemptedKey: String? = null
    private var autoFallbackTargetKey: String? = null
    private var lastRequestedHwdec: HwdecMode? = null

    @Volatile
    internal var latestDefaultHwdecMode: HwdecMode = HwdecMode.HARDWARE_DIRECT
        private set

    internal fun emitEvent(event: PlaybackSessionEvent) {
        _events.tryEmit(event)
    }

    internal fun updateDefaultHwdecMode(mode: HwdecMode) {
        latestDefaultHwdecMode = mode
    }

    internal fun resolveEffectiveHwdecKey(configuredDefaultHwdec: String): String = synchronized(lock) {
        when {
            autoFallbackTargetKey != null -> HwdecMode.SOFTWARE.persistedKey
            lastRequestedHwdec != null -> lastRequestedHwdec!!.persistedKey
            else -> configuredDefaultHwdec
        }
    }

    internal fun onDefaultHwdecChanged(newDefaultMode: HwdecMode) {
        synchronized(lock) {
            latestDefaultHwdecMode = newDefaultMode
            autoFallbackTargetKey = null
            lastRequestedHwdec = null
            hwdecFallbackAttemptedKey = null
        }
    }

    internal fun onExplicitHwdecSelected(mode: HwdecMode) {
        synchronized(lock) {
            autoFallbackTargetKey = null
            lastRequestedHwdec = mode
        }
    }

    internal fun prepareNewLoad(
        preserveHwdecOverride: Boolean,
        defaultHwdecMode: HwdecMode,
        setEngineHwdec: (HwdecMode) -> Unit,
    ) {
        latestDefaultHwdecMode = defaultHwdecMode
        if (preserveHwdecOverride) return
        val shouldRestore = synchronized(lock) {
            hwdecFallbackAttemptedKey = null
            _failureReason.value = null
            val hadOverride = autoFallbackTargetKey != null
            if (hadOverride) {
                autoFallbackTargetKey = null
                lastRequestedHwdec = null
            }
            hadOverride
        }
        if (shouldRestore) {
            setEngineHwdec(defaultHwdecMode)
        }
    }

    internal fun setSessionActive(active: Boolean) {
        val jobToCancel: Job?
        val monitorToCancel: Job?
        val sleepToCancel: Job?
        synchronized(lock) {
            _sessionActive.value = active
            if (!active) {
                loadGeneration++
                pendingLoadUri = null
                jobToCancel = activeLoadJob
                activeLoadJob = null
                monitorToCancel = trackingJob
                trackingJob = null
                sleepToCancel = sleepTimerJob
                sleepTimerJob = null
                _sleepTimerSecondsRemaining.value = 0L
                clearFailureStateLocked()
            } else {
                jobToCancel = null
                monitorToCancel = null
                sleepToCancel = null
            }
        }
        jobToCancel?.cancel()
        monitorToCancel?.cancel()
        sleepToCancel?.cancel()
    }

    internal fun setSleepTimer(
        minutes: Int,
        scope: CoroutineScope,
        onExpire: () -> Unit,
    ) {
        val previousJob: Job?
        synchronized(lock) {
            previousJob = sleepTimerJob
            if (minutes <= 0) {
                sleepTimerJob = null
                _sleepTimerSecondsRemaining.value = 0L
                previousJob?.cancel()
                return
            }
            _sleepTimerSecondsRemaining.value = minutes * 60L
            val newJob = scope.launch {
                while (_sleepTimerSecondsRemaining.value > 0L) {
                    delay(1_000L)
                    val remaining = (_sleepTimerSecondsRemaining.value - 1L).coerceAtLeast(0L)
                    _sleepTimerSecondsRemaining.value = remaining
                }
                onExpire()
            }
            sleepTimerJob = newJob
        }
        previousJob?.cancel()
    }

    /**
     * Ensures the session-bounded monitor is running while the session is active, automatically
     * persisting pause / 10s heartbeat checkpoints, auto-advancing on [PlaybackState.ENDED],
     * and classifying failures / falling back from hardware to software decoding independently of UI.
     */
    internal fun ensureSessionMonitor(
        scope: CoroutineScope,
        engine: PlayerEngine,
        playlist: Playlist,
        defaultHwdecFlow: Flow<HwdecMode>,
        onSaveProgress: () -> Unit,
        onAutoAdvanceNext: () -> Unit,
        onReloadUri: (Uri, Long, String?, Int, Int) -> Unit,
        onReloadRawTarget: (MediaItem) -> Unit,
    ) {
        synchronized(lock) {
            if (!_sessionActive.value || trackingJob?.isActive == true) return
            trackingJob = scope.launch {
                var advanceJob: Job? = null

                // 1. Track configured default decoder mode while the session is active
                launch {
                    defaultHwdecFlow.collect { mode ->
                        latestDefaultHwdecMode = mode
                    }
                }

                // 2. Auto-advance when video ends
                launch {
                    engine.playbackState.collect { state ->
                        if (state == PlaybackState.ENDED && playlist.hasNext) {
                            val endedIndex = playlist.currentIndex.value
                            advanceJob?.cancel()
                            advanceJob = launch {
                                delay(AUTO_ADVANCE_DELAY_MS)
                                if (!_sessionActive.value ||
                                    engine.isPlaying.value ||
                                    playlist.currentIndex.value != endedIndex ||
                                    !playlist.hasNext
                                ) {
                                    return@launch
                                }
                                onAutoAdvanceNext()
                            }
                        }
                    }
                }

                // 3. Persist progress immediately when playback transitions from playing to paused
                launch {
                    var wasPlaying = engine.isPlaying.value
                    engine.isPlaying.collect { playing ->
                        if (wasPlaying && !playing) {
                            onSaveProgress()
                        }
                        wasPlaying = playing
                    }
                }

                // 4. Periodic 10s checkpoint while playing (handles both forward playback and backward seeks)
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

                // 5. Session-scoped error classification & hwdec -> swdec fallback
                launch {
                    engine.state.collect { snapshot ->
                        onEngineSnapshot(
                            snapshot = snapshot,
                            defaultHwdecMode = latestDefaultHwdecMode,
                            currentTimePosMs = engine.timePosMs.value,
                            setEngineHwdec = engine::setHwdec,
                            reloadUri = onReloadUri,
                            reloadRawTarget = onReloadRawTarget,
                        )
                    }
                }
            }
        }
    }

    internal fun onEngineSnapshot(
        snapshot: PlaybackSnapshot,
        defaultHwdecMode: HwdecMode,
        currentTimePosMs: Long,
        setEngineHwdec: (HwdecMode) -> Unit,
        reloadUri: (Uri, Long, String?, Int, Int) -> Unit,
        reloadRawTarget: (MediaItem) -> Unit,
    ) {
        val failedTarget = (snapshot.session as? SessionPhase.Failed)?.target?.takeIf { it.isNotBlank() }
        val currentKey = snapshot.currentUri?.toString()?.takeIf { it.isNotBlank() } ?: failedTarget

        var shouldRestoreHwdec = false
        var actionError: Throwable? = null
        synchronized(lock) {
            if (!_sessionActive.value) return

            if (currentKey != null && autoFallbackTargetKey != null && currentKey != autoFallbackTargetKey) {
                autoFallbackTargetKey = null
                lastRequestedHwdec = null
                hwdecFallbackAttemptedKey = null
                shouldRestoreHwdec = true
            }

            if (snapshot.playbackState != PlaybackState.ERROR || snapshot.error == null) {
                lastHandledError = null
                _failureReason.value = null
                if (snapshot.playbackState == PlaybackState.READY &&
                    snapshot.hwdecCurrent.isNotBlank() &&
                    autoFallbackTargetKey == null
                ) {
                    lastRequestedHwdec = null
                }
            } else {
                val error = snapshot.error
                if (error != null && error !== lastHandledError) {
                    lastHandledError = error
                    actionError = error
                }
            }
        }

        if (shouldRestoreHwdec) {
            setEngineHwdec(defaultHwdecMode)
        }

        val error = actionError ?: return
        val runtimeHwdecMode = HwdecMode.fromRuntimeValue(snapshot.hwdecCurrent)
        val effectiveHwdecMode = synchronized(lock) {
            lastRequestedHwdec ?: runtimeHwdecMode ?: defaultHwdecMode
        }
        val effectiveHwdecLabel = snapshot.hwdecCurrent.ifBlank { effectiveHwdecMode.persistedKey }
        val targetUri = snapshot.currentUri
        val reason = classifyPlaybackFailure(error, targetUri, effectiveHwdecLabel)

        val isSourceOpenFailure =
            reason is PlaybackFailureReason.FileNotFound ||
                reason is PlaybackFailureReason.PermissionDenied
        val isHardwareActive = effectiveHwdecMode.isHardware

        val shouldFallback = synchronized(lock) {
            if (!isSourceOpenFailure &&
                isHardwareActive &&
                currentKey != null &&
                hwdecFallbackAttemptedKey != currentKey
            ) {
                hwdecFallbackAttemptedKey = currentKey
                autoFallbackTargetKey = currentKey
                lastRequestedHwdec = HwdecMode.SOFTWARE
                _failureReason.value = null
                true
            } else {
                _failureReason.value = reason
                false
            }
        }

        if (shouldFallback) {
            setEngineHwdec(HwdecMode.SOFTWARE)
            _events.tryEmit(
                PlaybackSessionEvent.HwdecAutoFallback(
                    from = effectiveHwdecMode,
                    to = HwdecMode.SOFTWARE,
                )
            )
            val resumePosMs = currentTimePosMs.coerceAtLeast(0L)
            val title = snapshot.mediaTitle.ifBlank { null }
            if (targetUri != null) {
                reloadUri(
                    targetUri,
                    resumePosMs,
                    title,
                    snapshot.videoDimensions.width,
                    snapshot.videoDimensions.height,
                )
            } else if (failedTarget != null) {
                reloadRawTarget(
                    MediaItem(
                        target = failedTarget,
                        title = title,
                        startPositionMs = resumePosMs,
                        initialDurationMs = snapshot.durationMs,
                        initialWidth = snapshot.videoDimensions.width,
                        initialHeight = snapshot.videoDimensions.height,
                    )
                )
            }
        } else {
            _events.tryEmit(PlaybackSessionEvent.ErrorOccurred(reason))
        }
    }

    private fun classifyPlaybackFailure(
        error: Throwable,
        uri: Uri?,
        hwdec: String,
    ): PlaybackFailureReason {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is SecurityException }) {
            return PlaybackFailureReason.PermissionDenied(uri ?: Uri.EMPTY)
        }
        if (chain.any { it is FileNotFoundException || it is PlaybackSourceOpenException }) {
            return PlaybackFailureReason.FileNotFound(uri ?: Uri.EMPTY)
        }
        val isCodecMessage = error.message?.let { msg ->
            msg.contains("codec", ignoreCase = true) || msg.contains("decoder", ignoreCase = true)
        } == true
        if (chain.any { it is EnginePlaybackException }) {
            return if (isCodecMessage) {
                PlaybackFailureReason.CodecCrash(hwdec.ifBlank { "unknown" })
            } else {
                PlaybackFailureReason.DemuxError(error.message ?: "Playback failed")
            }
        }
        return if (isCodecMessage) {
            PlaybackFailureReason.CodecCrash(hwdec.ifBlank { "unknown" })
        } else {
            PlaybackFailureReason.Unknown
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
     * Returns the configured default [HwdecMode] to restore if an auto-fallback override was
     * active when the session ended, or null if no override was active.
     */
    internal fun consumeAutoFallbackOnEnd(): HwdecMode? = synchronized(lock) {
        if (autoFallbackTargetKey != null) latestDefaultHwdecMode else null
    }

    /**
     * Atomically transitions the session from active to inactive and cancels any in-flight load.
     * Returns true if the session transitioned from active to inactive; false if already inactive.
     */
    internal fun endSession(): Boolean {
        val jobToCancel: Job?
        val monitorToCancel: Job?
        val sleepToCancel: Job?
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
            sleepToCancel = sleepTimerJob
            sleepTimerJob = null
            _sleepTimerSecondsRemaining.value = 0L
            clearFailureStateLocked()
        }
        jobToCancel?.cancel()
        monitorToCancel?.cancel()
        sleepToCancel?.cancel()
        return true
    }

    private fun clearFailureStateLocked() {
        lastHandledError = null
        hwdecFallbackAttemptedKey = null
        autoFallbackTargetKey = null
        lastRequestedHwdec = null
        _failureReason.value = null
    }
}
