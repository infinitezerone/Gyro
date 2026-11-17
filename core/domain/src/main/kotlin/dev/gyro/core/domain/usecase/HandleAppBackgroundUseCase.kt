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
 * 1. No-ops during Picture-in-Picture or when no active playing session exists.
 * 2. Checkpoints playback progress and detaches video surface immediately.
 * 3. Reads authoritative `backgroundAudio` setting from [SettingsRepository] and pauses playback
 *    if background audio is disabled.
 */
class HandleAppBackgroundUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val settingsRepository: SettingsRepository,
    private val saveProgressUseCase: SavePlaybackProgressUseCase,
    private val sessionStateHolder: PlaybackSessionStateHolder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(isPip: Boolean): Job? {
        if (isPip || !sessionStateHolder.sessionActive.value || !engine.isPlaying.value) {
            return null
        }
        saveProgressUseCase()
        engine.detachSurface()
        return scope.launch {
            val backgroundAudioEnabled = settingsRepository.settings.first().backgroundAudio
            if (!backgroundAudioEnabled && sessionStateHolder.sessionActive.value) {
                engine.pause()
            }
        }
    }
}
