package dev.gyro.core.domain.usecase

import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * UseCase to tear down the engine playback session:
 * 1. Saves current playback progress
 * 2. Stops engine hardware playback atomically (pause, abandon audio focus, detach surface)
 * 3. Optionally stops the foreground playback notification service
 */
class EndPlaybackSessionUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val serviceLauncher: PlaybackServiceLauncher,
) {
    operator fun invoke(requestServiceStop: Boolean = true) {
        saveProgressUseCase()
        engine.stop()
        if (requestServiceStop) {
            serviceLauncher.stop()
        }
    }
}
