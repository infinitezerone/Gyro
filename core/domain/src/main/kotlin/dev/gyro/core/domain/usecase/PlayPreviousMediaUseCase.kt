package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.contentUri
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to retreat the playlist cursor to the previous item and load it.
 * Returns true if a previous item existed and playback was initiated, false otherwise.
 */
class PlayPreviousMediaUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(): Boolean {
        val prev = engine.advanceToPreviousItem() ?: return false
        scope.launch {
            loadMediaUseCase(
                uri = prev.contentUri,
                displayTitle = prev.displayName,
                initialWidth = prev.width,
                initialHeight = prev.height
            )
        }
        return true
    }
}
