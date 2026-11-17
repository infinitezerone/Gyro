package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.contentUri
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to advance the playlist cursor to the next item and load it.
 * Returns true if an item existed and playback was initiated, false otherwise.
 */
class PlayNextMediaUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(): Boolean {
        val next = engine.advanceToNextItem() ?: return false
        scope.launch {
            loadMediaUseCase(
                uri = next.contentUri,
                displayTitle = next.displayName,
                initialWidth = next.width,
                initialHeight = next.height
            )
        }
        return true
    }
}
