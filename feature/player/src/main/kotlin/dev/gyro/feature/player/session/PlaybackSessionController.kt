package dev.gyro.feature.player.session

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.media.PlaylistManager
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHECKPOINT_INTERVAL_MS = 10_000L
private const val CHECKPOINT_MIN_DELTA_MS = 5_000L

/**
 * App-scoped owner of the playback session's business logic: playlist traversal, companion
 * subtitles, progress persistence and the load entry point.
 *
 * Lifetime is the process, not the player screen: background playback (and the auto-advance and
 * progress writes it needs) must survive the Activity being destroyed while the foreground service
 * keeps the engine alive. A single instance is created eagerly via [dev.gyro.player.GyroApplication]
 * and collects on the [ApplicationScope], so there is no per-screen instance to leak.
 *
 * The screen still binds foreground semantics through explicit signals rather than scope death:
 * [openUri] marks the session active, [exit] (called on user exit and service stop) clears it and
 * cancels a pending auto-advance.
 */
@Singleton
class PlaybackSessionController internal constructor(
    val engine: PlayerEngine,
    val settingsRepository: SettingsRepository,
    val historyRepository: PlaybackHistoryRepository,
    val subtitleRepository: SubtitleRepository,
    val mediaRepository: MediaRepository,
    val uriParser: UriParser,
    val playlist: PlaylistManager,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        engine: PlayerEngine,
        settingsRepository: SettingsRepository,
        historyRepository: PlaybackHistoryRepository,
        subtitleRepository: SubtitleRepository,
        mediaRepository: MediaRepository,
        uriParser: UriParser,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        engine = engine,
        settingsRepository = settingsRepository,
        historyRepository = historyRepository,
        subtitleRepository = subtitleRepository,
        mediaRepository = mediaRepository,
        uriParser = uriParser,
        playlist = engine.playlist,
        scope = appScope,
    )

    /** Single source of truth for the active URI directly from the engine store. */
    val activeUri: StateFlow<Uri?> get() = engine.currentUri

    /**
     * Whether a playback session is active (between [openUri] and [exit]). Exposed as a flow so the
     * foreground service can be hosted for the whole session, started while the UI is still visible.
     */
    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private var loadJob: Job? = null
    private var advanceJob: Job? = null

    init {
        // 1. Auto-advance to next video when current video completes (works in foreground & background)
        scope.launch {
            engine.playbackState.collect { state ->
                if (state == PlaybackState.ENDED && playlist.hasNext) {
                    val endedUri = engine.currentUri.value
                    advanceJob?.cancel()
                    advanceJob = scope.launch {
                        delay(500L)
                        // The grace delay is not a free pass: the user may have replayed, picked
                        // another video, or left the player during it. Re-check unambiguous intent
                        // signals and skip the advance if any of them fired.
                        if (!_sessionActive.value ||
                            engine.isPlaying.value ||
                            engine.currentUri.value != endedUri ||
                            !playlist.hasNext
                        ) {
                            return@launch
                        }
                        val next = playlist.playNext()
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
                    saveCurrentProgress()
                }
            }
        }

        // 4. Periodic checkpoint while playing. State is local to the coroutine loop;
        // collectLatest automatically suspends/cancels when playing becomes false.
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
            saveCurrentProgress(oldUri)
        }

        val playlistItem = playlist.items.value.firstOrNull { it.uriString == uri.toString() }
        val knownTitle = displayTitle?.takeIf { it.isNotBlank() }
            ?: playlistItem?.displayName?.takeIf { it.isNotBlank() }
        val resolvedWidth = if (initialWidth > 0) initialWidth else playlistItem?.width ?: 0
        val resolvedHeight = if (initialHeight > 0) initialHeight else playlistItem?.height ?: 0

        val job = scope.launch {
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
            // The session collects on the application scope (Default), but the load command must
            // stay on the main thread where surface attach/detach and engine lifecycle run.
            withContext(Dispatchers.Main) {
                engine.load(
                    uri = uri,
                    startPositionMs = startPos,
                    displayTitle = resolvedTitle,
                    initialDurationMs = initialDuration,
                    initialWidth = resolvedWidth,
                    initialHeight = resolvedHeight
                )
            }
        }
        loadJob = job
        return job
    }

    /**
     * Ends the foreground session: a pending auto-advance must not pull the next video up after the
     * user left the player or stopped playback from the notification.
     */
    fun exit() {
        _sessionActive.value = false
        advanceJob?.cancel()
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

    /** Persists the current playback progress using the snapshot state. */
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