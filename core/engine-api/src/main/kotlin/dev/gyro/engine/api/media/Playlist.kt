package dev.gyro.engine.api.media

import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the playback queue.
 *
 * The engine owns the queue the same way Media3's `Player` owns its `Timeline`: callers read it,
 * but only the engine moves the cursor ([dev.gyro.engine.api.PlayerEngine.advanceToNextItem]).
 * Keeping mutation off this interface is what makes "next video" have exactly one implementation.
 */
interface Playlist {
    val items: StateFlow<List<VideoMediaItem>>
    val currentIndex: StateFlow<Int>
    val currentItem: VideoMediaItem?
    val hasNext: Boolean
    val hasPrevious: Boolean
}
