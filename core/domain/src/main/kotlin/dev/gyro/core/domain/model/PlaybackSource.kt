package dev.gyro.core.domain.model

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem

/**
 * Describes the media source and queue strategy for [dev.gyro.core.domain.usecase.LoadMediaUseCase].
 */
sealed interface PlaybackSource {
    /** Replaces the playback queue with [playlist] and starts playing [targetItem]. */
    data class FromPlaylist(
        val playlist: List<VideoMediaItem>,
        val targetItem: VideoMediaItem,
    ) : PlaybackSource

    /**
     * Replaces the playback queue with a single item for [uri] and starts playing it.
     * When [displayTitle] is null or blank, the provider display name is resolved asynchronously
     * via `MediaRepository` instead of freezing a raw `content://` document ID.
     */
    data class Single(
        val uri: Uri,
        val displayTitle: String? = null,
        val skipIfAlreadyActive: Boolean = false,
    ) : PlaybackSource

    /**
     * Loads [uri] preserving the current playback queue (e.g. cursor step, retry, direct open).
     */
    data class UriItem(
        val uri: Uri,
        val displayTitle: String? = null,
        val initialWidth: Int = 0,
        val initialHeight: Int = 0,
        val skipIfAlreadyActive: Boolean = false,
    ) : PlaybackSource
}
