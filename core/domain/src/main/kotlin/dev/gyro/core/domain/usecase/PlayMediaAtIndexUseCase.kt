package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.contentUri
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to select an item at [index] within the playlist and load it.
 * Returns true if [index] was within bounds and playback was initiated, false otherwise.
 */
class PlayMediaAtIndexUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(index: Int): Boolean {
        val item = engine.selectItemAt(index) ?: return false
        scope.launch {
            loadMediaUseCase(
                uri = item.contentUri,
                displayTitle = item.displayName,
                initialWidth = item.width,
                initialHeight = item.height
            )
        }
        return true
    }
}
