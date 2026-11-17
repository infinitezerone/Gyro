package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem

/**
 * Interface contract for accessing local video media.
 *
 * Aggregates videos discovered from the system MediaStore and user-granted custom SAF folders.
 * All functions are main-safe.
 */
interface MediaRepository {
    /** Retrieves all accessible videos sorted by modification date descending. */
    suspend fun getVideos(): List<VideoMediaItem>

    /** Returns the provider's display name, or null when unavailable. Cancellation is propagated. */
    suspend fun getDisplayName(uri: Uri): String?
}
