package dev.gyro.feature.player.session

import android.net.Uri
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.media.PlaylistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * Coordinates playback session lifecycle and business workflows across UI and background services:
 * - Playlist traversal and auto-advancement on completion
 * - Companion subtitle scanning via [SubtitleRepository]
 * - Event-driven & periodic progress persistence via [PlaybackHistoryRepository]
 * - Auto-resume detection and duration resolution
 */
@Singleton
class PlaybackSessionOrchestrator(
    val engine: GyroPlayerEngine,
    val settingsRepository: SettingsRepository,
    val historyRepository: PlaybackHistoryRepository,
    val subtitleRepository: SubtitleRepository,
    val mediaRepository: MediaRepository,
    val uriParser: UriParser,
    val playlist: PlaylistManager = engine.playlist,
    scope: CoroutineScope? = null
) {
    @Inject
    constructor(
        engine: GyroPlayerEngine,
        settingsRepository: SettingsRepository,
        historyRepository: PlaybackHistoryRepository,
        subtitleRepository: SubtitleRepository,
        mediaRepository: MediaRepository,
        uriParser: UriParser
    ) : this(
        engine = engine,
        settingsRepository = settingsRepository,
        historyRepository = historyRepository,
        subtitleRepository = subtitleRepository,
        mediaRepository = mediaRepository,
        uriParser = uriParser,
        playlist = engine.playlist,
        scope = null
    )

    val orchestratorScope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activeUri = MutableStateFlow<Uri?>(engine.currentUri.value)
    val activeUri: StateFlow<Uri?> = _activeUri.asStateFlow()

    private var periodicSaveJob: Job? = null
    private var loadJob: Job? = null

    private var lastCheckpointUri: Uri? = null
    private var lastCheckpointPosMs: Long = 0L

    private fun shouldCheckpoint(uri: Uri, positionMs: Long): Boolean =
        uri != lastCheckpointUri || kotlin.math.abs(positionMs - lastCheckpointPosMs) >= CHECKPOINT_MIN_DELTA_MS

    init {
        // 1. Sync engine's currentUri changes into activeUri
        orchestratorScope.launch {
            engine.currentUri.collect { uri ->
                if (uri != null && uri != _activeUri.value) {
                    _activeUri.value = uri
                }
            }
        }

        // 2. Auto-advance to next video when current video completes (works in foreground & background)
        orchestratorScope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && playlist.hasNext) {
                    val endedUri = _activeUri.value
                    delay(500L)
                    // The grace delay is not a free pass: the user may have replayed or picked
                    // another video during it. Re-check unambiguous intent signals - resumed
                    // playback, or a different active uri - and skip the advance if either is
                    // true. Reading the global playback state alone is racy here: residual
                    // events from the finishing load can still land inside the window.
                    if (engine.isPlaying.value || _activeUri.value != endedUri || !playlist.hasNext) {
                        return@collect
                    }
                    val next = playlist.playNext()
                    if (next != null) {
                        playMediaItem(next)
                    }
                }
            }
        }

        // 3. Auto-load companion subtitles asynchronously via SubtitleRepository
        orchestratorScope.launch {
            _activeUri.collectLatest { uri ->
                if (uri != null && settingsRepository.settings.first().subAutoLoad) {
                    val knownDisplayName = playlist.items.value
                        .firstOrNull { it.uriString == uri.toString() }?.displayName
                    val subs = subtitleRepository.findCompanionSubtitles(uri, knownDisplayName)
                    subs.forEach { path -> engine.trackManager.addSubtitle(path) }
                }
            }
        }

        // 4. Event-driven progress persistence on pause
        orchestratorScope.launch {
            engine.isPlaying.collect { playing ->
                if (!playing) {
                    saveCurrentProgress()
                }
            }
        }

        // 5. Periodic checkpoint while playing
        periodicSaveJob = orchestratorScope.launch {
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

    fun openUri(uri: Uri, forceReload: Boolean = false, displayTitle: String? = null): Job {
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
            ?: playlist.items.value.firstOrNull { it.uriString == uri.toString() }
                ?.displayName?.takeIf { it.isNotBlank() }

        val job = orchestratorScope.launch {
            val resolvedTitle = knownTitle ?: mediaRepository.getDisplayName(uri)
            currentCoroutineContext().ensureActive()
            // Read through to the repository instead of the stateIn projection: a cold-start
            // deep link can reach here before DataStore's first emission lands, and deciding
            // auto-resume from the UserSettings() default would resume when the user turned it off.
            val autoResume = settingsRepository.settings.first().autoResume
            val progress = if (autoResume) historyRepository.getProgress(uri) else null
            val startPos = if (progress != null && !progress.isCompleted && progress.positionMs > 2000L) progress.positionMs else 0L
            val initialDuration = progress?.durationMs ?: 0L
            currentCoroutineContext().ensureActive()
            engine.load(uri, startPos, resolvedTitle, initialDurationMs = initialDuration)
        }
        loadJob = job
        return job
    }

    fun playMediaItem(item: VideoMediaItem) {
        openUri(uriParser.parse(item.uriString), displayTitle = item.displayName)
    }

    fun playNext(): Boolean {
        val next = playlist.playNext()
        return if (next != null) {
            playMediaItem(next)
            true
        } else {
            false
        }
    }

    fun playPrevious(): Boolean {
        val prev = playlist.playPrevious()
        return if (prev != null) {
            playMediaItem(prev)
            true
        } else {
            false
        }
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        val idx = items.indexOfFirst { it.uriString == item.uriString }
        playlist.setPlaylist(items, if (idx >= 0) idx else 0)
        playMediaItem(item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video") {
        playlist.setSingle(uri, displayName)
        openUri(uri, displayTitle = displayName)
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

    fun cancelJobs() {
        loadJob?.cancel()
        periodicSaveJob?.cancel()
    }
}
