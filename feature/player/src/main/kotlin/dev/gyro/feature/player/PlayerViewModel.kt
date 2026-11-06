package dev.gyro.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.data.repository.asAndroidUri
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * Business state container and orchestration pipeline for playback workflows.
 *
 * Hoists core playback side-effects out of Composable lifecycle:
 * - Auto-advancing playlist playback upon completion
 * - Companion subtitle scanning via [SubtitleRepository] (main-safe)
 * - Event-driven & throttled progress persistence
 * - Auto-resume detection and notice dispatch
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val engine: GyroPlayerEngine,
    private val settingsRepository: SettingsRepository,
    val historyRepository: PlaybackHistoryRepository,
    val playlistManager: PlaylistManager,
    private val subtitleRepository: SubtitleRepository
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlayerGestureConfig()
        )

    private val _activeUri = MutableStateFlow<Uri?>(engine.currentUri.value)
    val activeUri: StateFlow<Uri?> = _activeUri.asStateFlow()

    private var periodicSaveJob: Job? = null

    // Checkpoint bookkeeping lives in the ViewModel, not in the repository: it is session state,
    // and a singleton repository would keep it alive across player sessions.
    private var lastCheckpointUri: Uri? = null
    private var lastCheckpointPosMs: Long = 0L

    private fun shouldCheckpoint(uri: Uri, positionMs: Long): Boolean =
        uri != lastCheckpointUri || kotlin.math.abs(positionMs - lastCheckpointPosMs) >= CHECKPOINT_MIN_DELTA_MS

    init {
        // Sync engine's currentUri changes into activeUri
        viewModelScope.launch {
            engine.currentUri.collect { uri ->
                if (uri != null && uri != _activeUri.value) {
                    _activeUri.value = uri
                }
            }
        }

        // 1. Auto-advance to next video when current video completes
        viewModelScope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && playlistManager.hasNext) {
                    delay(500L)
                    val next = playlistManager.playNext()
                    if (next != null) {
                        playMediaItem(next)
                    }
                }
            }
        }

        // 2. Auto-load companion subtitles asynchronously via SubtitleRepository (main-safe)
        viewModelScope.launch {
            _activeUri.collectLatest { uri ->
                if (uri != null && settings.value.subAutoLoad) {
                    val knownDisplayName = playlistManager.items.value
                        .firstOrNull { it.uriString == uri.toString() }?.displayName
                    val subs = subtitleRepository.findCompanionSubtitles(uri, knownDisplayName)
                    subs.forEach { path -> engine.addSubtitle(path) }
                }
            }
        }

        // 3. Event-driven progress persistence on pause
        viewModelScope.launch {
            engine.isPlaying.collect { playing ->
                if (!playing) {
                    saveCurrentProgress()
                }
            }
        }

        // 4. Periodic checkpoint while playing: the timer itself is the rate limiter, the delta
        // check only skips periods where the position did not move enough to be worth a write.
        periodicSaveJob = viewModelScope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (playing) {
                    while (isActive) {
                        delay(CHECKPOINT_INTERVAL_MS)
                        val uri = _activeUri.value
                        if (uri != null) {
                            val curPos = engine.timePosMs.value
                            val curDur = engine.durationMs.value
                            if (curDur > 0L && shouldCheckpoint(uri, curPos)) {
                                lastCheckpointUri = uri
                                lastCheckpointPosMs = curPos
                                historyRepository.saveProgress(uri, curPos, curDur)
                            }
                        }
                    }
                }
            }
        }

    }

    fun openUri(uri: Uri, forceReload: Boolean = false) {
        _activeUri.value?.let { oldUri ->
            if (oldUri != uri) {
                val curPos = engine.timePosMs.value
                val curDur = engine.durationMs.value
                if (curDur > 0L) {
                    historyRepository.saveProgress(oldUri, curPos, curDur)
                }
            }
        }

        _activeUri.value = uri

        // getProgress is a suspend read now that the repository is stateless, so the load is
        // dispatched instead of blocking the caller for a database round trip.
        viewModelScope.launch {
            val startPos = if (settings.value.autoResume) {
                val p = historyRepository.getProgress(uri)
                if (p != null && !p.isCompleted && p.positionMs > 2000L) p.positionMs else 0L
            } else 0L
            engine.loadUri(uri, startPos)
        }
    }

    fun playMediaItem(item: VideoMediaItem) {
        openUri(item.asAndroidUri)
    }

    fun playNext(): Boolean {
        val next = playlistManager.playNext()
        return if (next != null) {
            playMediaItem(next)
            true
        } else {
            false
        }
    }

    fun playPrevious(): Boolean {
        val prev = playlistManager.playPrevious()
        return if (prev != null) {
            playMediaItem(prev)
            true
        } else {
            false
        }
    }

    fun saveCurrentProgress() {
        engine.resetSoftwareGain()
        val uri = _activeUri.value ?: return
        val curPos = engine.timePosMs.value
        val curDur = engine.durationMs.value
        if (curDur > 0L) {
            historyRepository.saveProgress(uri, curPos, curDur)
        }
    }

    public override fun onCleared() {
        super.onCleared()
        periodicSaveJob?.cancel()
        saveCurrentProgress()
    }
}
