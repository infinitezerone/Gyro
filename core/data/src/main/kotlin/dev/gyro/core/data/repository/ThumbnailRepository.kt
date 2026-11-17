package dev.gyro.core.data.repository

import android.graphics.Bitmap
import android.net.Uri

/**
 * Repository interface for retrieving and caching video frame thumbnails.
 */
interface ThumbnailRepository {
    suspend fun getThumbnail(uri: Uri?, timeMs: Long): Bitmap?
    fun release()
}
