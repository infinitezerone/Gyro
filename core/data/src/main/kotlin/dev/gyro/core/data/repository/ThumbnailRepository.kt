package dev.gyro.core.data.repository

import android.graphics.Bitmap
import android.net.Uri
import dev.gyro.core.model.MediaPalette

/**
 * Repository interface for retrieving and caching video frame thumbnails, poster artwork,
 * and extracted color palettes. Implementations must guarantee all suspend calls are main-safe.
 */
interface ThumbnailRepository {
    suspend fun getThumbnail(uri: Uri?, timeMs: Long): Bitmap?
    suspend fun getArtwork(uri: Uri?, width: Int = 720, height: Int = 1280): Bitmap? = null
    suspend fun extractPalette(bitmap: Bitmap?): MediaPalette = MediaPalette()
    fun release()
}
