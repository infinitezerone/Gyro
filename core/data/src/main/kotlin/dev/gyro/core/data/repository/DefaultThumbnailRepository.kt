package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.palette.graphics.Palette
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.MediaPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val THUMBNAIL_WIDTH = 240
private const val THUMBNAIL_HEIGHT = 136
private const val MAX_CACHE_ENTRIES = 256
private const val MAX_ARTWORK_ENTRIES = 16

/**
 * Thread-safe, main-safe implementation of [ThumbnailRepository] using injected
 * [GyroDispatchers.IO] and [GyroDispatchers.Default] dispatchers.
 */
@Singleton
class DefaultThumbnailRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @Dispatcher(GyroDispatchers.Default) private val defaultDispatcher: CoroutineDispatcher,
) : ThumbnailRepository {

    private val mutex = Mutex()
    private var currentUri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null

    private val cache = LruCache<String, Bitmap>(MAX_CACHE_ENTRIES)
    private val artworkCache = LruCache<String, Bitmap>(MAX_ARTWORK_ENTRIES)
    private val paletteCache = LruCache<Int, MediaPalette>(MAX_ARTWORK_ENTRIES)

    override suspend fun getThumbnail(uri: Uri?, timeMs: Long): Bitmap? {
        if (uri == null) return null
        val key = "${uri}_$timeMs"
        cache.get(key)?.let { return it }

        return withContext(ioDispatcher) {
            mutex.withLock {
                coroutineContext.ensureActive()
                cache.get(key)?.let { return@withLock it }

                runCatching {
                    if (currentUri != uri || retriever == null) {
                        if (currentUri != null && currentUri != uri) {
                            cache.evictAll()
                        }
                        releaseRetrieverLocked()
                        val fresh = MediaMetadataRetriever()
                        try {
                            configureDataSource(fresh, uri)
                        } catch (t: Throwable) {
                            runCatching { fresh.release() }
                            throw t
                        }
                        retriever = fresh
                        currentUri = uri
                    }
                    val mmr = retriever ?: return@withLock null
                    coroutineContext.ensureActive()
                    val timeUs = (timeMs * 1000L).coerceAtLeast(0L)
                    val bitmap = mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                        THUMBNAIL_WIDTH,
                        THUMBNAIL_HEIGHT,
                    ) ?: mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        THUMBNAIL_WIDTH,
                        THUMBNAIL_HEIGHT,
                    )
                    if (bitmap != null) {
                        cache.put(key, bitmap)
                    }
                    bitmap
                }.getOrNull()
            }
        }
    }

    override suspend fun getArtwork(uri: Uri?, width: Int, height: Int): Bitmap? {
        if (uri == null || width <= 0 || height <= 0) return null
        val key = "${uri}_${width}x$height"
        artworkCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
            artworkCache.remove(key)
        }

        return withContext(ioDispatcher) {
            coroutineContext.ensureActive()
            artworkCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withContext it }

            val decoded = try {
                val targetSize = Size(width, height)
                when {
                    uri.scheme == ContentResolver.SCHEME_CONTENT -> {
                        context.contentResolver.loadThumbnail(uri, targetSize, null)
                    }
                    else -> {
                        val path = uri.path
                        if (path != null && File(path).exists()) {
                            ThumbnailUtils.createVideoThumbnail(File(path), targetSize, null)
                        } else {
                            MediaMetadataRetriever().use { mmr ->
                                configureDataSource(mmr, uri)
                                mmr.getScaledFrameAtTime(
                                    0L,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    width,
                                    height,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }

            if (decoded != null) {
                artworkCache.put(key, decoded)
            }
            decoded
        }
    }

    override suspend fun extractPalette(bitmap: Bitmap?): MediaPalette {
        if (bitmap == null || bitmap.isRecycled) return MediaPalette()
        val cacheKey = System.identityHashCode(bitmap)
        paletteCache.get(cacheKey)?.let { return it }

        return withContext(defaultDispatcher) {
            coroutineContext.ensureActive()
            if (bitmap.isRecycled) return@withContext MediaPalette()
            try {
                val palette = Palette.from(bitmap)
                    .maximumColorCount(16)
                    .generate()

                val dominant = palette.dominantSwatch?.rgb
                val vibrant = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: MediaPalette.DEFAULT_ACCENT_ARGB
                val darkMuted = palette.darkMutedSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: MediaPalette.DEFAULT_DARK_MUTED_ARGB

                MediaPalette(
                    dominantArgb = dominant,
                    accentArgb = vibrant,
                    darkMutedArgb = darkMuted,
                ).also { paletteCache.put(cacheKey, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Palette extraction failed, falling back to defaults", t)
                MediaPalette()
            }
        }
    }

    override fun release() {
        cache.evictAll()
        artworkCache.evictAll()
        paletteCache.evictAll()
        if (mutex.tryLock()) {
            try {
                releaseRetrieverLocked()
            } finally {
                mutex.unlock()
            }
        } else {
            scope.launch(ioDispatcher) {
                mutex.withLock {
                    releaseRetrieverLocked()
                    cache.evictAll()
                }
            }
        }
    }

    private fun configureDataSource(mmr: MediaMetadataRetriever, uri: Uri) {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE) {
            mmr.setDataSource(context, uri)
        } else {
            val path = uri.path
            if (path != null && File(path).exists()) {
                mmr.setDataSource(path)
            } else {
                mmr.setDataSource(context, uri)
            }
        }
    }

    private fun releaseRetrieverLocked() {
        runCatching { retriever?.release() }
        retriever = null
        currentUri = null
    }

    private companion object {
        const val TAG = "DefaultThumbnailRepo"
    }
}
