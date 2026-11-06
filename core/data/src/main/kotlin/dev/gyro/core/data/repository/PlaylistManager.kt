package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages the current playlist / playback queue for continuous playback and episode switching.
 */
class PlaylistManager {

    private val _items = MutableStateFlow<List<VideoMediaItem>>(emptyList())
    val items: StateFlow<List<VideoMediaItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    fun setPlaylist(newItems: List<VideoMediaItem>, startIndex: Int) {
        _items.value = newItems
        _currentIndex.value = startIndex.coerceIn(-1, newItems.size - 1)
    }

    val currentItem: VideoMediaItem?
        get() {
            val idx = _currentIndex.value
            val list = _items.value
            return if (idx in list.indices) list[idx] else null
        }

    val hasPrevious: Boolean
        get() = _currentIndex.value > 0

    val hasNext: Boolean
        get() {
            val idx = _currentIndex.value
            val list = _items.value
            return idx >= 0 && idx < list.size - 1
        }

    fun playNext(): VideoMediaItem? {
        if (!hasNext) return null
        _currentIndex.update { it + 1 }
        return currentItem
    }

    fun playPrevious(): VideoMediaItem? {
        if (!hasPrevious) return null
        _currentIndex.update { it - 1 }
        return currentItem
    }

    fun playAtIndex(index: Int): VideoMediaItem? {
        val list = _items.value
        if (index !in list.indices) return null
        _currentIndex.value = index
        return list[index]
    }

    fun setSingle(uri: Uri, displayName: String) {
        val item = VideoMediaItem(
            id = System.currentTimeMillis(),
            uriString = uri.toString(),
            displayName = displayName,
            durationMs = 0L,
            sizeBytes = 0L,
            width = 0,
            height = 0,
            dateModified = 0L,
            folderName = ""
        )
        _items.value = listOf(item)
        _currentIndex.value = 0
    }
}
