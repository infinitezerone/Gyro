package dev.gyro.engine.media

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [MediaItem] into something mpv can load.
 *
 * An interface so a test (or a headless harness without a provider) can answer loads without a
 * package manager: the JVM suite cannot open content descriptors at all.
 */
interface MediaResourceResolver {
    suspend fun resolve(item: MediaItem): ResolvedMediaSource

    class MediaOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)
}

/**
 * Resolves off the main thread; content URIs are opened here and the descriptor's ownership travels
 * with [ResolvedMediaSource], so a load that is cancelled or replaced before `loadfile` releases it
 * instead of leaking it. Every other scheme is handed over as a path: mpv opens those itself.
 */
@Singleton
class DefaultMediaResourceResolver @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaResourceResolver {

    companion object {
        private const val TAG = "MediaResourceResolver"
    }

    override suspend fun resolve(item: MediaItem): ResolvedMediaSource = withContext(Dispatchers.IO) {
        val uri = item.uri
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val descriptor = try {
                context.contentResolver?.openFileDescriptor(uri, "r")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.w(TAG, "cannot open a descriptor for $uri", t)
                null
            } ?: throw MediaResourceResolver.MediaOpenException("Cannot open a descriptor for $uri")

            ResolvedMediaSource(uri.toString(), descriptor)
        } else {
            ResolvedMediaSource(uri.path ?: uri.toString())
        }
    }
}
