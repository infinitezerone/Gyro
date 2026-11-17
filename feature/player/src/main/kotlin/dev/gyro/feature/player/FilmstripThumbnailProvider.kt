package dev.gyro.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe provider and cache for video filmstrip thumbnails.
 *
 * Encapsulates:
 * 1. Single-instance [MediaMetadataRetriever] reuse across frames to avoid repeated setDataSource()
 * 2. 256-entry in-memory LRU cache
 * 3. High-throughput OPTION_CLOSEST_SYNC fast-path with graceful fallback
 * 4. Proper native resource disposal on player tear-down
 */
@Singleton
class FilmstripThumbnailProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mutex = Mutex()
    private var currentUri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null

    private val cache = LruCache<String, Bitmap>(256)

    suspend fun getThumbnail(uri: Uri?, timeMs: Long): Bitmap? {
        if (uri == null) return null
        val key = "${uri}_$timeMs"
        val cached = cache.get(key)
        if (cached != null) return cached

        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val cachedLocked = cache.get(key)
                if (cachedLocked != null) return@withLock cachedLocked

                runCatching {
                    if (currentUri != uri || retriever == null) {
                        retriever?.release()
                        retriever = MediaMetadataRetriever().apply {
                            if (uri.scheme == "content" || uri.scheme == "android.resource") {
                                setDataSource(context, uri)
                            } else {
                                val path = uri.path
                                if (path != null && File(path).exists()) {
                                    setDataSource(path)
                                } else {
                                    setDataSource(context, uri)
                                }
                            }
                        }
                        currentUri = uri
                    }
                    val mmr = retriever ?: return@withLock null
                    val timeUs = (timeMs * 1000L).coerceAtLeast(0L)
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        mmr.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            240,
                            135
                        ) ?: mmr.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            240,
                            135
                        )
                    } else {
                        mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    }
                    if (bitmap != null) {
                        cache.put(key, bitmap)
                    }
                    bitmap
                }.getOrNull()
            }
        }
    }

    fun release() {
        mutex.tryLock().let { locked ->
            if (locked) {
                try {
                    retriever?.release()
                    retriever = null
                    currentUri = null
                    cache.evictAll()
                } finally {
                    mutex.unlock()
                }
            }
        }
    }
}
