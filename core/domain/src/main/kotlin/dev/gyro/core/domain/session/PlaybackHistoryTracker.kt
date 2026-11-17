package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * App-scoped domain service that manages playback history checkpoints and playlist auto-advancement.
 * Strictly bounded by [PlaybackSessionStateHolder.sessionActive]: all tracking jobs are started
 * upon session activation and cancelled immediately upon session deactivation.
 */
@Singleton
class PlaybackHistoryTracker @Inject constructor(
    private val engine: PlayerEngine,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val navigatePlaylistUseCase: NavigatePlaylistUseCase,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var observerJob: Job? = null
    private var trackingJob: Job? = null
    private var advanceJob: Job? = null

    /**
     * Ensures the session lifecycle observer is active. Idempotent and safe to call multiple times.
     */
    fun startObserving() {
        synchronized(lock) {
            if (observerJob?.isActive == true) return
            observerJob = scope.launch {
                sessionStateHolder.sessionActive.collect { active ->
                    if (active) {
                        startTracking()
                    } else {
                        stopTracking()
                    }
                }
            }
        }
    }

    private fun startTracking() {
        if (trackingJob?.isActive == true) return
        trackingJob = scope.launch {
            // 1. Auto-advance when video ends
            launch {
                engine.playbackState.collect { state ->
                    if (state == PlaybackState.ENDED && engine.playlist.hasNext) {
                        val endedIndex = engine.playlist.currentIndex.value
                        advanceJob?.cancel()
                        advanceJob = launch {
                            delay(500L)
                            if (!sessionStateHolder.sessionActive.value ||
                                engine.isPlaying.value ||
                                engine.playlist.currentIndex.value != endedIndex ||
                                !engine.playlist.hasNext
                            ) {
                                return@launch
                            }
                            navigatePlaylistUseCase(PlaylistStep.Next)
                        }
                    }
                }
            }

            // 2. Persist progress immediately when playback transitions from playing to paused
            launch {
                var wasPlaying = engine.isPlaying.value
                engine.isPlaying.collect { playing ->
                    if (wasPlaying && !playing) {
                        saveProgressUseCase()
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
                            saveProgressUseCase()
                            lastCheckpointUri = uri
                            lastCheckpointPosMs = pos
                        }
                    }
                }
            }
        }
    }

    private fun stopTracking() {
        advanceJob?.cancel()
        advanceJob = null
        trackingJob?.cancel()
        trackingJob = null
    }
}
