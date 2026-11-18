package dev.gyro.core.domain.usecase

import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * Authoritative single-method UseCase to tear down the playback session across all exit paths:
 * 1. Saves current playback progress before clearing engine state
 * 2. Atomically resets session active state, failure/fallback state, sleep timer, and cancels in-flight loads
 * 3. Clears active domain queue cursor and restores default hardware decoder mode if auto-fallback occurred
 * 4. Stops engine hardware playback and clears engine snapshot state
 * 5. Releases filmstrip thumbnail decoder and bitmap cache
 * 6. Stops the foreground notification service when [reason] is [SessionEndReason.USER_EXIT]
 */
class EndPlaybackSessionUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val thumbnailRepository: ThumbnailRepository,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    private val playlistStateHolder: PlaylistStateHolder,
) {
    operator fun invoke(reason: SessionEndReason = SessionEndReason.USER_EXIT) {
        val restoreHwdec = sessionStateHolder.consumeAutoFallbackOnEnd()
        if (!sessionStateHolder.endSession()) {
            return
        }
        saveProgressUseCase()
        playlistStateHolder.clear()
        if (restoreHwdec != null) {
            engine.setHwdec(restoreHwdec)
        }
        engine.stop()
        thumbnailRepository.release()
        if (reason.shouldStopService) {
            serviceLauncher.stop()
        }
    }
}
