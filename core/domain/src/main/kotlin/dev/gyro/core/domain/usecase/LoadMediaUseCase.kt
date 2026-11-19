package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.model.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Single-method UseCase orchestrating media loading across all [PlaybackSource] variants:
 * 1. Checkpoints current progress and resets transient viewport zoom/pan on track change.
 * 2. Updates `:core:domain` [PlaylistStateHolder] queue atomically when requested (`FromPlaylist` / `Single`).
 * 3. Deduplicates already-active or in-flight playback when `skipIfAlreadyActive` is enabled.
 * 4. Cancels any superseded in-flight load and guards async steps with a monotonic generation token.
 * 5. Resolves missing titles, auto-resume positions, hwdec override restoration, and companion
 *    subtitles via main-safe repositories without performing manual dispatcher switching.
 * 6. Keeps the session monitor and background playback service active.
 */
class LoadMediaUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val subtitleRepository: SubtitleRepository,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val playlistStateHolder: PlaylistStateHolder,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(source: PlaybackSource): Job? {
        val targetUri = when (source) {
            is PlaybackSource.FromPlaylist -> source.targetItem.contentUri
            is PlaybackSource.Single -> source.uri
            is PlaybackSource.UriItem -> source.uri
        }

        val skipIfAlreadyActive = when (source) {
            is PlaybackSource.FromPlaylist -> false
            is PlaybackSource.Single -> source.skipIfAlreadyActive
            is PlaybackSource.UriItem -> source.skipIfAlreadyActive
        }

        val currentEngineUri = engine.currentUri.value
        val state = engine.playbackState.value
        val alreadyActive = sessionStateHolder.isSessionHandlingUri(
            uri = targetUri,
            currentEngineUri = currentEngineUri,
            playbackState = state,
        )

        if (skipIfAlreadyActive && alreadyActive) {
            if (source is PlaybackSource.Single) {
                val existingItems = playlistStateHolder.items.value
                val inPlaylist = existingItems.any { it.uriString == targetUri.toString() }
                if (!inPlaylist) {
                    val title = source.displayTitle?.takeIf { it.isNotBlank() }
                        ?: engine.state.value.mediaTitle.ifBlank { targetUri.lastPathSegment ?: "Video" }
                    playlistStateHolder.setSingle(targetUri, title)
                }
            }
            sessionStateHolder.setSessionActive(true)
            ensureSessionMonitorActive()
            serviceLauncher.start()
            return null
        }

        if (currentEngineUri != null && currentEngineUri != targetUri) {
            saveProgressUseCase()
        }

        val preserveHwdecOverride = (source as? PlaybackSource.UriItem)?.preserveHwdecOverride == true
        val startPosOverrideMs = (source as? PlaybackSource.UriItem)?.startPositionOverrideMs
        if (!preserveHwdecOverride) {
            engine.geometry.resetZoomAndPan()
        }

        val knownTitle: String?
        val resolvedWidth: Int
        val resolvedHeight: Int

        when (source) {
            is PlaybackSource.FromPlaylist -> {
                val startIdx = source.playlist
                    .indexOfFirst { it.id == source.targetItem.id || it.uriString == source.targetItem.uriString }
                    .coerceAtLeast(0)
                playlistStateHolder.setPlaylist(source.playlist, startIdx)
                knownTitle = source.targetItem.displayName.takeIf { it.isNotBlank() }
                resolvedWidth = source.targetItem.width
                resolvedHeight = source.targetItem.height
            }
            is PlaybackSource.Single -> {
                knownTitle = source.displayTitle?.takeIf { it.isNotBlank() }
                playlistStateHolder.setSingle(targetUri, knownTitle ?: "")
                resolvedWidth = 0
                resolvedHeight = 0
            }
            is PlaybackSource.UriItem -> {
                val matched = playlistStateHolder.items.value.firstOrNull { it.uriString == targetUri.toString() }
                knownTitle = source.displayTitle?.takeIf { it.isNotBlank() }
                    ?: matched?.displayName?.takeIf { it.isNotBlank() }
                resolvedWidth = if (source.initialWidth > 0) source.initialWidth else (matched?.width ?: 0)
                resolvedHeight = if (source.initialHeight > 0) source.initialHeight else (matched?.height ?: 0)
            }
        }

        val job = sessionStateHolder.launchLoad(
            uri = targetUri,
            scope = scope,
        ) { isCurrentLoad ->
            val settings = settingsRepository.settings.first()
            currentCoroutineContext().ensureActive()
            if (!isCurrentLoad()) return@launchLoad

            sessionStateHolder.prepareNewLoad(
                preserveHwdecOverride = preserveHwdecOverride,
                defaultHwdecMode = settings.defaultHwdecMode,
                setEngineHwdec = engine::setHwdec,
            )

            val resolvedTitle = knownTitle ?: mediaRepository.getDisplayName(targetUri)
            currentCoroutineContext().ensureActive()
            if (!isCurrentLoad()) return@launchLoad

            val progress = if (startPosOverrideMs != null) {
                null
            } else {
                historyRepository.getProgress(targetUri)
            }
            val startPos = startPosOverrideMs?.coerceAtLeast(0L)
                ?: if (settings.autoResume && progress != null && !progress.isCompleted && progress.positionMs >= 2000L) {
                    progress.positionMs
                } else {
                    0L
                }
            val initialDuration = progress?.durationMs
                ?: if (startPosOverrideMs != null) engine.state.value.durationMs else 0L
            currentCoroutineContext().ensureActive()
            if (!isCurrentLoad()) return@launchLoad

            if (source is PlaybackSource.Single && knownTitle == null) {
                playlistStateHolder.setSingle(targetUri, resolvedTitle ?: targetUri.lastPathSegment ?: "Video")
            }
            engine.load(
                uri = targetUri,
                startPositionMs = startPos,
                displayTitle = resolvedTitle,
                initialDurationMs = initialDuration,
                initialWidth = resolvedWidth,
                initialHeight = resolvedHeight,
            )

            if (settings.subAutoLoad && isCurrentLoad()) {
                val loadedSnapshot = engine.state.first {
                    !isCurrentLoad() ||
                        (it.currentUri == targetUri &&
                            (it.session is SessionPhase.Loaded || it.session is SessionPhase.Failed))
                }
                currentCoroutineContext().ensureActive()
                if (isCurrentLoad() && loadedSnapshot.session is SessionPhase.Loaded) {
                    val subUris = subtitleRepository.findCompanionSubtitles(targetUri, resolvedTitle)
                    currentCoroutineContext().ensureActive()
                    for (subUri in subUris) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentLoad()) break
                        subtitleRepository.openSubtitle(subUri) { subTarget ->
                            if (!isCurrentLoad() || engine.currentUri.value != targetUri) {
                                false
                            } else {
                                engine.trackManager.addSubtitle(subTarget)
                                true
                            }
                        }
                    }
                }
            }
        }
        ensureSessionMonitorActive()
        serviceLauncher.start()
        return job
    }

    private fun ensureSessionMonitorActive() {
        sessionStateHolder.ensureSessionMonitor(
            scope = scope,
            engine = engine,
            playlist = playlistStateHolder,
            defaultHwdecFlow = settingsRepository.settings.map { it.defaultHwdecMode }.distinctUntilChanged(),
            onSaveProgress = { saveProgressUseCase() },
            onAutoAdvanceNext = {
                val next = playlistStateHolder.autoAdvance() ?: return@ensureSessionMonitor
                invoke(
                    PlaybackSource.UriItem(
                        uri = next.contentUri,
                        displayTitle = next.displayName,
                        initialWidth = next.width,
                        initialHeight = next.height,
                    )
                )
            },
            onReloadUri = { uri, resumePosMs, title, width, height ->
                invoke(
                    PlaybackSource.UriItem(
                        uri = uri,
                        displayTitle = title,
                        initialWidth = width,
                        initialHeight = height,
                        skipIfAlreadyActive = false,
                        startPositionOverrideMs = resumePosMs,
                        preserveHwdecOverride = true,
                    )
                )
            },
            onReloadRawTarget = { rawItem ->
                engine.load(rawItem)
            },
        )
    }
}
