package dev.gyro.feature.home.thumbnail

import android.content.Context
import coil3.ImageLoader
import coil3.video.VideoFrameDecoder

/**
 * Creates or retrieves a process-level [ImageLoader] optimized specifically for video thumbnails:
 *
 * 1. [ContentThumbnailFetcher] fetches pre-rendered system thumbnails (MediaStore and SAF) in
 *    milliseconds with zero [android.media.MediaMetadataRetriever] overhead.
 * 2. [VideoFrameDecoder] serves as a reliable fallback when system thumbnails are unavailable.
 */
object VideoThumbnailLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(ContentThumbnailFetcher.Factory(context.applicationContext))
                    add(VideoFrameDecoder.Factory())
                }
                .build()
                .also { instance = it }
        }
    }
}
