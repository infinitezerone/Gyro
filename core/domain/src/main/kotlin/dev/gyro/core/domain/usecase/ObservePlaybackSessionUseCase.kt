package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.PlaybackSessionInfo
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-method UseCase exposing a shared, synchronously-accurate [StateFlow] of [PlaybackSessionInfo],
 * decoupling UI hosts (such as `MainActivityViewModel`) from [PlayerEngine] and internal session state.
 */
@Singleton
class ObservePlaybackSessionUseCase @Inject constructor(
    sessionStateHolder: PlaybackSessionStateHolder,
    engine: PlayerEngine,
    @ApplicationScope scope: CoroutineScope,
) {
    private val sessionFlow: StateFlow<PlaybackSessionInfo> = combine(
        sessionStateHolder.sessionActive,
        engine.currentUri,
    ) { active, uri ->
        PlaybackSessionInfo(isActive = active, activeUri = uri)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = PlaybackSessionInfo(
            isActive = sessionStateHolder.sessionActive.value,
            activeUri = engine.currentUri.value,
        ),
    )

    operator fun invoke(): StateFlow<PlaybackSessionInfo> = sessionFlow
}
