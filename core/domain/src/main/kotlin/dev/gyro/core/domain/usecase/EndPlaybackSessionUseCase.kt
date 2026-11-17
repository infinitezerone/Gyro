package dev.gyro.core.domain.usecase

import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * UseCase to tear down the engine playback session:
 * 1. Safely resets session active state via CAS
 * 2. Saves current playback progress
 * 3. Stops engine hardware playback atomically (pause, abandon audio focus, detach surface)
 * 4. Optionally stops the foreground playback notification service
 */
class EndPlaybackSessionUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStateHolder: PlaybackSessionStateHolder,
) {
    operator fun invoke(requestServiceStop: Boolean = true) {
        if (!sessionStateHolder.compareAndSetSessionActive(expect = true, update = false)) {
            return
        }
        saveProgressUseCase()
        engine.stop()
        if (requestServiceStop) {
            serviceLauncher.stop()
        }
    }
}
