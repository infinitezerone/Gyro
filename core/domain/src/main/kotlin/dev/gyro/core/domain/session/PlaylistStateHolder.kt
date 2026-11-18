package dev.gyro.core.domain.session

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mutable application-scoped queue holder owned by `:core:domain`.
 *
 * External modules depend only on the read-only [Playlist] view; all mutators are `internal` to
 * `:core:domain` so every queue change passes through domain UseCases.
 */
@Singleton
class PlaylistStateHolder @Inject constructor() : Playlist {

    private val _items = MutableStateFlow<List<VideoMediaItem>>(emptyList())
    override val items: StateFlow<List<VideoMediaItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    @Synchronized
    internal fun setPlaylist(newItems: List<VideoMediaItem>, startIndex: Int) {
        _items.value = newItems
        _currentIndex.value = startIndex.coerceIn(-1, newItems.size - 1)
    }

    override val currentItem: VideoMediaItem?
        @Synchronized get() {
            val idx = _currentIndex.value
            val list = _items.value
            return if (idx in list.indices) list[idx] else null
        }

    override val hasPrevious: Boolean
        @Synchronized get() = _currentIndex.value > 0

    override val hasNext: Boolean
        @Synchronized get() {
            val idx = _currentIndex.value
            val list = _items.value
            return idx >= 0 && idx < list.size - 1
        }

    @Synchronized
    internal fun playNext(): VideoMediaItem? {
        if (!hasNext) return null
        _currentIndex.value += 1
        return currentItem
    }

    @Synchronized
    internal fun playPrevious(): VideoMediaItem? {
        if (!hasPrevious) return null
        _currentIndex.value -= 1
        return currentItem
    }

    @Synchronized
    internal fun playAtIndex(index: Int): VideoMediaItem? {
        val list = _items.value
        if (index !in list.indices) return null
        _currentIndex.value = index
        return list[index]
    }

    @Synchronized
    internal fun setSingle(uriString: String, displayName: String) {
        val item = VideoMediaItem(
            id = System.currentTimeMillis(),
            uriString = uriString,
            displayName = displayName,
            durationMs = 0L,
            sizeBytes = 0L,
            width = 0,
            height = 0,
            dateModified = 0L,
            folderName = "",
        )
        _items.value = listOf(item)
        _currentIndex.value = 0
    }

    @Synchronized
    internal fun setSingle(uri: Uri, displayName: String) {
        setSingle(uri.toString(), displayName)
    }

    @Synchronized
    internal fun clear() {
        _items.value = emptyList()
        _currentIndex.value = -1
    }
}
