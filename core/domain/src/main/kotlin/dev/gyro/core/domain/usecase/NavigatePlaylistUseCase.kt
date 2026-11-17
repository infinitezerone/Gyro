package dev.gyro.core.domain.usecase

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.engine.api.PlayerEngine
import javax.inject.Inject

/**
 * UseCase to handle playlist traversal and updates:
 * - Advancing / retreating cursor
 * - Selecting playlist item by index
 * - Setting new playlist or single media item
 */
class NavigatePlaylistUseCase @Inject constructor(
    private val engine: PlayerEngine,
) {
    fun playNext(): VideoMediaItem? {
        return engine.advanceToNextItem()
    }

    fun playPrevious(): VideoMediaItem? {
        return engine.advanceToPreviousItem()
    }

    fun playAtIndex(index: Int): VideoMediaItem? {
        return engine.selectItemAt(index)
    }

    fun setPlaylist(items: List<VideoMediaItem>, item: VideoMediaItem): VideoMediaItem {
        val idx = items.indexOfFirst { it.uriString == item.uriString }
        engine.setPlaylist(items, if (idx >= 0) idx else 0)
        return item
    }

    fun setSingleItem(uri: Uri, displayName: String = uri.lastPathSegment ?: "Video") {
        engine.setSingleItem(uri, displayName)
    }
}
