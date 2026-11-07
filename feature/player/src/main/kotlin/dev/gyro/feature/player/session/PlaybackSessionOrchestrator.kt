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
import kotlinx.coroutines.flow.StateFlow
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

    /** Single source of truth for the active URI directly from the engine store. */
    val activeUri: StateFlow<Uri?> get() = engine.currentUri

    private var loadJob: Job? = null

    init {
        // 1. Auto-advance to next video when current video completes (works in foreground & background)
        orchestratorScope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && playlist.hasNext) {
                    val endedUri = engine.currentUri.value
                    delay(500L)
                    // The grace delay is not a free pass: the user may have replayed or picked
                    // another video during it. Re-check unambiguous intent signals - resumed
                    // playback, or a different active uri - and skip the advance if either is
                    // true.
                    if (engine.isPlaying.value || engine.currentUri.value != endedUri || !playlist.hasNext) {
                        return@collect
                    }
                    val next = playlist.playNext()
                    if (next != null) {
                        playMediaItem(next)
                    }
                }
            }
        }

        // 2. Auto-load companion subtitles asynchronously via SubtitleRepository
        orchestratorScope.launch {
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
        orchestratorScope.launch {
            engine.isPlaying.collect { playing ->
                if (!playing) {
                    saveCurrentProgress()
                }
            }
        }

        // 4. Periodic checkpoint while playing. State is local to the coroutine loop;
        // collectLatest automatically suspends/cancels when playing becomes false.
        orchestratorScope.launch {
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
        initialHeight: Int = 0
    ): Job {
        loadJob?.cancel()
        val oldUri = engine.currentUri.value
        if (oldUri != null && oldUri != uri) {
            saveCurrentProgress(oldUri)
        }

        val playlistItem = playlist.items.value.firstOrNull { it.uriString == uri.toString() }
        val knownTitle = displayTitle?.takeIf { it.isNotBlank() }
            ?: playlistItem?.displayName?.takeIf { it.isNotBlank() }
        val resolvedWidth = if (initialWidth > 0) initialWidth else playlistItem?.width ?: 0
        val resolvedHeight = if (initialHeight > 0) initialHeight else playlistItem?.height ?: 0

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
            engine.load(
                uri = uri,
                startPositionMs = startPos,
                displayTitle = resolvedTitle,
                initialDurationMs = initialDuration,
                initialWidth = resolvedWidth,
                initialHeight = resolvedHeight
            )
        }
        loadJob = job
        return job
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

    /**
     * Atomically saves the current playback progress using the snapshot state.
     * Pure function with zero side-effects.
     */
    fun saveCurrentProgress(targetUri: Uri? = null) {
        val snapshot = engine.state.value
        val uri = targetUri ?: snapshot.currentUri ?: return
        val curPos = engine.timePosMs.value
        val curDur = snapshot.durationMs
        if (curDur > 0L) {
            historyRepository.saveProgress(uri, curPos, curDur)
        }
    }

    fun cancelPendingLoad() {
        loadJob?.cancel()
    }
}
