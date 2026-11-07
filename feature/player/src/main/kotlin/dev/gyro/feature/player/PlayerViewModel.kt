package dev.gyro.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.playlist.PlaylistManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val subtitleRepository: SubtitleRepository,
    private val uriParser: UriParser,
    private val mediaRepository: MediaRepository,
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

    /**
     * Bumped on every playback request. The player screen composes through Nav3, which can reuse a
     * popped entry's composition when the same route re-enters, so `remember` state survives;
     * anything the screen must reset per-entry (like the controls visibility) keys off this.
     */
    private val _screenEntryEpoch = MutableStateFlow(0)
    val screenEntryEpoch: StateFlow<Int> = _screenEntryEpoch.asStateFlow()

    fun notifyScreenEntry() {
        _screenEntryEpoch.value += 1
    }

    private var periodicSaveJob: Job? = null
    private var loadJob: Job? = null

    // Checkpoint bookkeeping lives in the ViewModel, not in the repository: it is session state,
    // and a singleton repository would keep it alive across player sessions.
    private var lastCheckpointUri: Uri? = null
    private var lastCheckpointPosMs: Long = 0L

    private fun shouldCheckpoint(uri: Uri, positionMs: Long): Boolean =
        uri != lastCheckpointUri || kotlin.math.abs(positionMs - lastCheckpointPosMs) >= CHECKPOINT_MIN_DELTA_MS

    private val registeredLauncher: (VideoMediaItem) -> Unit = ::playMediaItem

    init {
        playlistManager.mediaItemLauncher = registeredLauncher

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
                    subs.forEach { path -> engine.trackManager.addSubtitle(path) }
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

    fun openUri(uri: Uri, forceReload: Boolean = false, displayTitle: String? = null) {
        loadJob?.cancel()
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

        val knownTitle = displayTitle?.takeIf { it.isNotBlank() }
            ?: playlistManager.items.value.firstOrNull { it.uriString == uri.toString() }
                ?.displayName?.takeIf { it.isNotBlank() }

        // A provider may ignore cancellation; reject its stale result before touching the engine.
        loadJob = viewModelScope.launch {
            val resolvedTitle = knownTitle ?: mediaRepository.getDisplayName(uri)
            currentCoroutineContext().ensureActive()
            val progress = if (settings.value.autoResume) historyRepository.getProgress(uri) else null
            val startPos = if (progress != null && !progress.isCompleted && progress.positionMs > 2000L) progress.positionMs else 0L
            val initialDuration = progress?.durationMs ?: 0L
            currentCoroutineContext().ensureActive()
            engine.load(uri, startPos, resolvedTitle, initialDurationMs = initialDuration)
        }
    }

    fun playMediaItem(item: VideoMediaItem) {
        openUri(uriParser.parse(item.uriString), displayTitle = item.displayName)
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
        engine.audio.resetSoftwareGain()
        val uri = _activeUri.value ?: return
        val curPos = engine.timePosMs.value
        val curDur = engine.durationMs.value
        if (curDur > 0L) {
            historyRepository.saveProgress(uri, curPos, curDur)
        }
    }

    public override fun onCleared() {
        super.onCleared()
        if (playlistManager.mediaItemLauncher === registeredLauncher) {
            playlistManager.mediaItemLauncher = null
        }
        loadJob?.cancel()
        periodicSaveJob?.cancel()
        saveCurrentProgress()
    }
}
