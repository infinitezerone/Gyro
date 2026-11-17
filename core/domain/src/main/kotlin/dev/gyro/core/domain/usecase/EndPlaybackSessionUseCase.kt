package dev.gyro.core.domain.usecase

import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * Authoritative single-method UseCase to tear down the playback session across all exit paths:
 * 1. Atomically resets session active state and cancels any in-flight media load
 * 2. Saves current playback progress
 * 3. Stops engine hardware playback (pause, abandon audio focus, detach surface)
 * 4. Releases filmstrip thumbnail decoder and bitmap cache
 * 5. Stops the foreground notification service when [reason] is [SessionEndReason.USER_EXIT]
 */
class EndPlaybackSessionUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val thumbnailRepository: ThumbnailRepository,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStateHolder: PlaybackSessionStateHolder,
) {
    operator fun invoke(reason: SessionEndReason = SessionEndReason.USER_EXIT) {
        if (!sessionStateHolder.endSession()) {
            return
        }
        saveProgressUseCase()
        engine.stop()
        thumbnailRepository.release()
        if (reason.shouldStopService) {
            serviceLauncher.stop()
        }
    }
}
