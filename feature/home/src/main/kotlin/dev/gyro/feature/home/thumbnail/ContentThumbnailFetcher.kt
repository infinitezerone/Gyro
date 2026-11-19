package dev.gyro.feature.home.thumbnail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.CancellationException

/**
 * High-performance Coil 3 [Fetcher] for video thumbnails.
 *
 * Prioritizes [ContentResolver.loadThumbnail] on Android 10+ (API 29+), allowing the system's
 * hardware-accelerated, pre-cached thumbnails to be returned directly without launching
 * [android.media.MediaMetadataRetriever].
 *
 * If the system call fails or is unsupported, returning `null` gracefully falls back to the
 * standard pipeline and [coil3.video.VideoFrameDecoder].
 */
class ContentThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

        val width = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_WIDTH
        val height = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_HEIGHT
        val targetSize = Size(width.coerceAtLeast(64), height.coerceAtLeast(64))

        val signal = CancellationSignal()
        return try {
            val bitmap = context.contentResolver.loadThumbnail(uri, targetSize, signal)
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        } catch (e: CancellationException) {
            signal.cancel()
            throw e
        } catch (_: Throwable) {
            // Unsupported provider, revoked grant or corrupted media falls back to next decoder
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != ContentResolver.SCHEME_CONTENT) return null
            return ContentThumbnailFetcher(context, data, options)
        }
    }

    companion object {
        const val DEFAULT_WIDTH = 320
        const val DEFAULT_HEIGHT = 180
    }
}
