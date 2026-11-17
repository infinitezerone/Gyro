package dev.gyro.core.domain.usecase

import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.domain.model.contentUri
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to populate the playback queue with [items] and begin playing [targetItem].
 */
class SetPlaylistAndPlayUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    operator fun invoke(items: List<VideoMediaItem>, targetItem: VideoMediaItem): Job {
        val idx = items.indexOfFirst { it.uriString == targetItem.uriString }
        engine.setPlaylist(items, if (idx >= 0) idx else 0)
        return scope.launch {
            loadMediaUseCase(
                uri = targetItem.contentUri,
                displayTitle = targetItem.displayName,
                initialWidth = targetItem.width,
                initialHeight = targetItem.height
            )
        }
    }
}
