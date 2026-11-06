package dev.gyro.feature.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.data.repository.CompanionSubtitleScanner
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.data.repository.asAndroidUri
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Business state container and orchestration pipeline for playback workflows.
 *
 * Hoists core playback side-effects out of Composable lifecycle:
 * - Auto-advancing playlist playback upon completion
 * - Companion subtitle scanning and attaching
 * - Periodic progress persistence and dismissal save
 * - Auto-resume detection and notice dispatch
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val engine: GyroPlayerEngine,
    private val settingsRepository: SettingsRepository,
    val historyRepository: PlaybackHistoryRepository,
    val playlistManager: PlaylistManager
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings

    val gestureConfig: StateFlow<PlayerGestureConfig> = settingsRepository.settings
        .map { it.gestureConfig }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = settingsRepository.settings.value.gestureConfig
        )

    private val _activeUri = MutableStateFlow<Uri?>(engine.currentUri.value)
    val activeUri: StateFlow<Uri?> = _activeUri.asStateFlow()

    private val _resumeNoticePosMs = MutableStateFlow<Long?>(null)
    val resumeNoticePosMs: StateFlow<Long?> = _resumeNoticePosMs.asStateFlow()

    private var hasShownResumeNotice: Boolean = false
    private var pendingResumeNoticePosMs: Long? = null
    private var periodicSaveJob: Job? = null

    private fun triggerResumeNoticeIfNeeded() {
        val resumePos = pendingResumeNoticePosMs ?: return
        if (!hasShownResumeNotice && engine.playbackState.value == PlaybackState.READY) {
            hasShownResumeNotice = true
            pendingResumeNoticePosMs = null
            _resumeNoticePosMs.value = resumePos
            viewModelScope.launch {
                delay(2500L)
                _resumeNoticePosMs.value = null
            }
        }
    }

    init {
        // Sync engine's currentUri changes into activeUri
        viewModelScope.launch {
            engine.currentUri.collect { uri ->
                if (uri != null && uri != _activeUri.value) {
                    _activeUri.value = uri
                    hasShownResumeNotice = false
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

        // 2. Auto-load companion subtitles asynchronously on IO dispatcher
        viewModelScope.launch {
            _activeUri.collectLatest { uri ->
                if (uri != null && settings.value.subAutoLoad) {
                    val knownDisplayName = playlistManager.items.value
                        .firstOrNull { it.uriString == uri.toString() }?.displayName
                    withContext(Dispatchers.IO) {
                        val subs = CompanionSubtitleScanner.findCompanionSubtitles(context, uri, knownDisplayName)
                        subs.forEach { path -> engine.addSubtitle(path) }
                    }
                }
            }
        }

        // 3. Periodic progress persistence (every 4s while playing)
        periodicSaveJob = viewModelScope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (playing) {
                    while (isActive) {
                        delay(4000L)
                        val uri = _activeUri.value
                        if (uri != null) {
                            val curPos = engine.timePosMs.value
                            val curDur = engine.durationMs.value
                            if (curDur > 0L) {
                                historyRepository.saveProgress(uri, curPos, curDur)
                            }
                        }
                    }
                }
            }
        }

        // 4. Auto-resume position notification
        viewModelScope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.READY) {
                    triggerResumeNoticeIfNeeded()
                }
            }
        }
    }

    fun notifyResumedAt(positionMs: Long) {
        if (positionMs > 2000L) {
            _resumeNoticePosMs.value = positionMs
            viewModelScope.launch {
                delay(2500L)
                _resumeNoticePosMs.value = null
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
        hasShownResumeNotice = false

        val startPos = if (settings.value.autoResume) {
            val p = historyRepository.getProgress(uri)
            if (p != null && !p.isCompleted && p.positionMs > 2000L) p.positionMs else 0L
        } else 0L

        if (startPos > 2000L) {
            pendingResumeNoticePosMs = startPos
        } else {
            pendingResumeNoticePosMs = null
        }

        engine.loadUri(uri, startPos)
        triggerResumeNoticeIfNeeded()
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

    fun clearResumeNotice() {
        _resumeNoticePosMs.value = null
    }

    public override fun onCleared() {
        super.onCleared()
        periodicSaveJob?.cancel()
        saveCurrentProgress()
    }
}
