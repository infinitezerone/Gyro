package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-method UseCase handling application transition to background (e.g. `Activity.onStop`):
 * 1. No-ops during Picture-in-Picture or when no active session exists.
 * 2. Checkpoints playback progress and detaches video surface immediately.
 * 3. Reads authoritative `backgroundAudio` setting from [SettingsRepository]:
 *    - When enabled, permits background/headless loading so auto-advance & notification skips keep running.
 *    - When disabled, disallows headless loading and pauses playback.
 */
class HandleAppBackgroundUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val settingsRepository: SettingsRepository,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var backgroundJob: Job? = null

    operator fun invoke(isPip: Boolean): Job? {
        if (isPip || !sessionStateHolder.sessionActive.value) {
            return null
        }
        saveProgressUseCase()
        engine.render.detachSurface()
        backgroundJob?.cancel()
        val job = scope.launch {
            val backgroundAudioEnabled = settingsRepository.settings.first().backgroundAudio
            if (!sessionStateHolder.sessionActive.value) return@launch
            if (backgroundAudioEnabled) {
                engine.setBackgroundPlaybackAllowed(true)
            } else {
                engine.setBackgroundPlaybackAllowed(false)
                engine.pause()
            }
        }
        backgroundJob = job
        return job
    }

    /**
     * Handles explicit user dismissal of the Picture-in-Picture window:
     * saves progress, detaches the surface, disarms headless background playback, and pauses
     * playback while keeping the session active so returning to the app stays on the player screen.
     */
    fun onPipDismissed() {
        if (!sessionStateHolder.sessionActive.value) {
            return
        }
        backgroundJob?.cancel()
        backgroundJob = null
        saveProgressUseCase()
        engine.render.detachSurface()
        engine.setBackgroundPlaybackAllowed(false)
        engine.pause()
    }
}
