package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UseCase to handle playlist traversal and updates:
 * - Advancing / retreating cursor and launching media loads asynchronously
 * - Selecting playlist item by index
 * - Setting new playlist or single media item
 */
class NavigatePlaylistUseCase @Inject constructor(
    private val engine: PlayerEngine,
    private val uriParser: UriParser,
    private val loadMediaUseCase: LoadMediaUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun playMediaItem(item: VideoMediaItem): Job {
        return scope.launch {
            loadMediaUseCase(
                uri = uriParser.parse(item.uriString),
                displayTitle = item.displayName,
                initialWidth = item.width,
                initialHeight = item.height
            )
        }
    }

    fun playNext(): Boolean {
        val next = engine.advanceToNextItem() ?: return false
        playMediaItem(next)
        return true
    }

    fun playPrevious(): Boolean {
        val prev = engine.advanceToPreviousItem() ?: return false
        playMediaItem(prev)
        return true
    }

    fun playAtIndex(index: Int): Boolean {
        val item = engine.selectItemAt(index) ?: return false
        playMediaItem(item)
        return true
    }

    fun setPlaylistAndPlay(items: List<VideoMediaItem>, item: VideoMediaItem) {
        val idx = items.indexOfFirst { it.uriString == item.uriString }
        engine.setPlaylist(items, if (idx >= 0) idx else 0)
        playMediaItem(item)
    }

    fun setSingleAndPlay(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video"): Job {
        engine.setSingleItem(uri, displayName)
        return scope.launch {
            loadMediaUseCase(uri, displayTitle = displayName)
        }
    }
}
