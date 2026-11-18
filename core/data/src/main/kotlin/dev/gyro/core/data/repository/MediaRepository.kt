package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interface contract for accessing local video media.
 *
 * Aggregates videos discovered from the system MediaStore and user-granted custom SAF folders.
 * All functions are main-safe.
 *
 * The library contents are owned here, not by whoever happens to be observing. MediaStore is
 * external data whose changes the app cannot predict, so its cache has to outlive any single
 * screen's subscription. Consumers observe [observeVideos] and ask for a rescan with
 * [requestRefresh].
 */
interface MediaRepository {
    /** Retrieves all accessible videos sorted by modification date descending. */
    suspend fun getVideos(): List<VideoMediaItem>

    /** Returns the provider's display name, or null when unavailable. Cancellation is propagated. */
    suspend fun getDisplayName(uri: Uri): String?

    /**
     * Last known library contents, or `null` while nothing has been scanned on this process.
     *
     * The `null` case is what keeps "unknown" distinguishable from "empty": a consumer must not
     * render an empty library before the first scan has actually reported one.
     */
    fun observeVideos(): StateFlow<List<VideoMediaItem>?> = MutableStateFlow(null).asStateFlow()

    /** Requests a rescan. Coalesced, so calling it on every trigger is safe. */
    fun requestRefresh() {}

    /** Starts a scan if the library has never been read on this process. */
    fun ensureLoaded() {}
}
