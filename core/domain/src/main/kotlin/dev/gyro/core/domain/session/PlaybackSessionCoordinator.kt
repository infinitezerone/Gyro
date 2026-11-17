package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.media.Playlist
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * Domain-level coordinator managing the lifecycle of an active playback session.
 * Coordinates domain event loops (auto-advance, subtitle auto-loading, periodic checkpoints)
 * and delegates explicit operations to focused UseCases.
 */
@Singleton
class PlaybackSessionCoordinator @Inject constructor(
    val engine: PlayerEngine,
    val settingsRepository: SettingsRepository,
    val historyRepository: PlaybackHistoryRepository,
    val subtitleRepository: SubtitleRepository,
    val mediaRepository: MediaRepository,
    val uriParser: UriParser,
    val loadMediaUseCase: LoadMediaUseCase,
    val saveProgressUseCase: SavePlaybackProgressUseCase,
    val endPlaybackSessionUseCase: EndPlaybackSessionUseCase,
    val navigatePlaylistUseCase: NavigatePlaylistUseCase,
    @ApplicationScope private val scope: CoroutineScope,
    private val serviceLauncher: PlaybackServiceLauncher = PlaybackServiceLauncher.NoOp,
) {

    constructor(
        engine: PlayerEngine,
        settingsRepository: SettingsRepository,
        historyRepository: PlaybackHistoryRepository,
        subtitleRepository: SubtitleRepository,
        mediaRepository: MediaRepository,
        uriParser: UriParser,
        scope: CoroutineScope,
        serviceLauncher: PlaybackServiceLauncher = PlaybackServiceLauncher.NoOp,
    ) : this(
        engine = engine,
        settingsRepository = settingsRepository,
        historyRepository = historyRepository,
        subtitleRepository = subtitleRepository,
        mediaRepository = mediaRepository,
        uriParser = uriParser,
        loadMediaUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = mediaRepository,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            serviceLauncher = serviceLauncher
        ),
        saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepository),
        endPlaybackSessionUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepository),
            serviceLauncher = serviceLauncher
        ),
        navigatePlaylistUseCase = NavigatePlaylistUseCase(engine),
        scope = scope,
        serviceLauncher = serviceLauncher,
    )

    /** Read-only queue view; the engine owns the cursor. */
    val playlist: Playlist get() = engine.playlist

    /** Single source of truth for the active URI directly from the engine store. */
    val activeUri: StateFlow<Uri?> get() = engine.currentUri

    /**
     * Whether a playback session is active (between [openUri] and [endSession]).
     */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var loadJob: Job? = null
    private var advanceJob: Job? = null

    init {
        // 1. Auto-advance to next video when current video completes
        scope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && playlist.hasNext) {
                    val endedIndex = playlist.currentIndex.value
                    advanceJob?.cancel()
                    advanceJob = scope.launch {
                        delay(500L)
                        if (!_sessionActive.value ||
                            engine.isPlaying.value ||
                            playlist.currentIndex.value != endedIndex ||
                            !playlist.hasNext
                        ) {
                            return@launch
                        }
                        val next = navigatePlaylistUseCase.playNext()
                        if (next != null) {
                            playMediaItem(next)
                        }
                    }
                }
            }
        }

        // 2. Auto-load companion subtitles asynchronously via SubtitleRepository
        scope.launch {
            engine.currentUri.collectLatest { uri ->
                if (uri != null && settingsRepository.settings.first().subAutoLoad) {
                    val knownDisplayName = playlist.items.value
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

    fun openUri(
        uri: Uri,
        forceReload: Boolean = false,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
    ): Job {
        _sessionActive.value = true
        loadJob?.cancel()
        val oldUri = engine.currentUri.value
        if (oldUri != null && oldUri != uri) {
            saveProgressUseCase(oldUri)
        }

        val job = scope.launch {
            loadMediaUseCase(
                uri = uri,
                displayTitle = displayTitle,
                initialWidth = initialWidth,
                initialHeight = initialHeight,
            )
        }
        loadJob = job
        return job
    }

    /**
     * Teardown the playback session with idempotent CAS state check.
     */
    fun endSession(requestServiceStop: Boolean = true) {
        if (!_sessionActive.compareAndSet(expect = true, update = false)) return
        advanceJob?.cancel()
        loadJob?.cancel()
        endPlaybackSessionUseCase(requestServiceStop = requestServiceStop)
    }

    fun leavePlayer() {
        endSession(requestServiceStop = true)
    }

    fun onNotificationStop() {
        endSession(requestServiceStop = false)
    }

    fun onServiceDestroyed() {
        endSession(requestServiceStop = false)
    }

    fun exit() {
        endSession(requestServiceStop = true)
    }

    fun playMediaItem(item: VideoMediaItem) {
        openUri(
            uriParser.parse(item.uriString),
            displayTitle = item.displayName,
            initialWidth = item.width,
            initialHeight = item.height
        )
    }

    fun playNext(): Boolean {
        val next = navigatePlaylistUseCase.playNext() ?: return false
        playMediaItem(next)
        return true
    }

    fun playPrevious(): Boolean {
        val prev = navigatePlaylistUseCase.playPrevious() ?: return false
        playMediaItem(prev)
        return true
    }

    fun playAtIndex(index: Int): Boolean {
        val item = navigatePlaylistUseCase.playAtIndex(index) ?: return false
        playMediaItem(item)
        return true
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        val selected = navigatePlaylistUseCase.setPlaylist(items, item)
        playMediaItem(selected)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video") {
        navigatePlaylistUseCase.setSingleItem(uri, displayName)
        openUri(uri, displayTitle = displayName)
    }

    fun saveCurrentProgress(targetUri: Uri? = null) {
        saveProgressUseCase(targetUri)
    }

    fun cancelPendingLoad() {
        loadJob?.cancel()
    }
}
