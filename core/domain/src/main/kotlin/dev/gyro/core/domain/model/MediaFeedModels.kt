package dev.gyro.core.domain.model

import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem

data class MediaFeedQuery(
    val hasPermission: Boolean = false,
    val refreshVersion: Int = 0,
    val selectedFolder: String? = null,
    val searchQuery: String = "",
)

/**
 * What the home screen knows about the media library.
 *
 * This used to be a flat `isLoading: Boolean` next to the video lists, which made
 * `(isLoading = false, videos = empty)` carry two opposite meanings — "scanned, there is nothing"
 * and "not scanned yet". Consumers had to guess, and guessed wrong on the way back from the
 * player, which surfaced as the "no videos" placeholder flashing before the list appeared.
 * Splitting the unknown case out makes that state unrepresentable.
 */
sealed interface MediaFeedState {
    /** Nothing is known yet: no snapshot has been produced for this screen. */
    data object Loading : MediaFeedState

    /**
     * A real snapshot. An empty list here means the library really is empty.
     *
     * [refreshing] marks a scan that is running behind an already-usable snapshot, so a revisit can
     * keep showing the previous list instead of dropping to a spinner.
     */
    data class Ready(
        val rawVideos: List<VideoMediaItem> = emptyList(),
        val filteredVideos: List<VideoMediaItem> = emptyList(),
        val folders: List<String> = emptyList(),
        val effectiveSelectedFolder: String? = null,
        val playbackMap: Map<String, PlaybackProgress> = emptyMap(),
        val refreshing: Boolean = false,
    ) : MediaFeedState

    /** Videos before filtering, `0` while nothing is known yet. */
    val rawVideoCount: Int
        get() = (this as? Ready)?.rawVideos?.size ?: 0
}
