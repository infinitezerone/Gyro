package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * Process-scoped background observer that monitors engine flow emissions and applies
 * reactive domain policies (auto-advance, companion subtitle loading, progress autosaving).
 */
@Singleton
class PlaybackSessionObserver @Inject constructor(
    private val engine: PlayerEngine,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val subtitleRepository: SubtitleRepository,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val navigatePlaylistUseCase: NavigatePlaylistUseCase,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var advanceJob: Job? = null

    init {
        // 1. Auto-advance to next video when current video completes
        scope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && engine.playlist.hasNext) {
                    val endedIndex = engine.playlist.currentIndex.value
                    advanceJob?.cancel()
                    advanceJob = scope.launch {
                        delay(500L)
                        if (!sessionStateHolder.sessionActive.value ||
                            engine.isPlaying.value ||
                            engine.playlist.currentIndex.value != endedIndex ||
                            !engine.playlist.hasNext
                        ) {
                            return@launch
                        }
                        navigatePlaylistUseCase.playNext()
                    }
                }
            }
        }

        // Cancel pending advance whenever session becomes inactive
        scope.launch {
            sessionStateHolder.sessionActive.collect { active ->
                if (!active) {
                    advanceJob?.cancel()
                }
            }
        }

        // 2. Auto-load companion subtitles asynchronously
        scope.launch {
            engine.currentUri.collectLatest { uri ->
                if (uri != null && settingsRepository.settings.first().subAutoLoad) {
                    val knownDisplayName = engine.playlist.items.value
                        .firstOrNull { it.uriString == uri.toString() }?.displayName
                    val subs = subtitleRepository.findCompanionSubtitles(uri, knownDisplayName)
                    subs.forEach { path -> engine.trackManager.addSubtitle(path) }
                }
            }
        }

        // 3. Event-driven progress persistence on pause
        scope.launch {
            engine.isPlaying.collect { playing ->
                if (!playing) {
                    saveProgressUseCase()
                }
            }
        }

        // 4. Periodic checkpoint while playing
        scope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                var lastCheckpointUri: Uri? = null
                var lastCheckpointPosMs = 0L
                while (isActive) {
                    delay(CHECKPOINT_INTERVAL_MS)
                    val snapshot = engine.state.value
                    val uri = snapshot.currentUri ?: continue
                    val curPos = engine.timePosMs.value
                    val curDur = snapshot.durationMs
                    if (curDur > 0L && (uri != lastCheckpointUri || kotlin.math.abs(curPos - lastCheckpointPosMs) >= CHECKPOINT_MIN_DELTA_MS)) {
                        lastCheckpointUri = uri
                        lastCheckpointPosMs = curPos
                        historyRepository.saveProgress(uri, curPos, curDur)
                    }
                }
            }
        }
    }

    fun cancelPendingAdvance() {
        advanceJob?.cancel()
    }
}
