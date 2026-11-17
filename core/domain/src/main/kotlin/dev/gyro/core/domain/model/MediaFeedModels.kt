package dev.gyro.core.domain.model

import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem

data class MediaFeedQuery(
    val hasPermission: Boolean = false,
    val refreshVersion: Int = 0,
    val selectedFolder: String? = null,
    val searchQuery: String = "",
)

data class MediaFeed(
    val isLoading: Boolean = true,
    val rawVideos: List<VideoMediaItem> = emptyList(),
    val filteredVideos: List<VideoMediaItem> = emptyList(),
    val folders: List<String> = emptyList(),
    val effectiveSelectedFolder: String? = null,
    val playbackMap: Map<String, PlaybackProgress> = emptyMap(),
)
