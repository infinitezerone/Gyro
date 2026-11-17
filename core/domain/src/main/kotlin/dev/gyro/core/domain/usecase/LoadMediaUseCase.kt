package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * UseCase to load a media resource into the playback engine:
 * 1. Activates the session in [PlaybackSessionStateHolder]
 * 2. Starts the foreground playback service
 * 3. Persists previous video progress if URI changed
 * 4. Resolves media title and dimensions
 * 5. Checks auto-resume settings and retrieves previous playback progress
 * 6. Invokes the engine load routine on the Main dispatcher
 */
class LoadMediaUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val mediaRepository: MediaRepository,
    private val historyRepository: PlaybackHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
) {
    suspend operator fun invoke(
        uri: Uri,
        displayTitle: String? = null,
        initialWidth: Int = 0,
        initialHeight: Int = 0,
    ) {
        sessionStateHolder.setSessionActive(true)
        serviceLauncher.start()

        val oldUri = engine.currentUri.value
        if (oldUri != null && oldUri != uri) {
            saveProgressUseCase(oldUri)
        }

        val playlistItem = engine.playlist.items.value.firstOrNull { it.uriString == uri.toString() }
        val knownTitle = displayTitle?.takeIf { it.isNotBlank() }
            ?: playlistItem?.displayName?.takeIf { it.isNotBlank() }
        val resolvedWidth = if (initialWidth > 0) initialWidth else playlistItem?.width ?: 0
        val resolvedHeight = if (initialHeight > 0) initialHeight else playlistItem?.height ?: 0

        val resolvedTitle = knownTitle ?: mediaRepository.getDisplayName(uri)
        currentCoroutineContext().ensureActive()

        val autoResume = settingsRepository.settings.first().autoResume
        val progress = if (autoResume) historyRepository.getProgress(uri) else null
        val startPos = if (progress != null && !progress.isCompleted && progress.positionMs > 2000L) progress.positionMs else 0L
        val initialDuration = progress?.durationMs ?: 0L
        currentCoroutineContext().ensureActive()

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
}
