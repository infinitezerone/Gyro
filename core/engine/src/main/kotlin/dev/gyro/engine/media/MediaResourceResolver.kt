package dev.gyro.engine.media

import android.content.ContentResolver
import android.content.Context
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Turns a [MediaItem] into something mpv can load.
 *
 * An interface so a test (or a headless harness without a provider) can answer loads without a
 * package manager: the JVM suite cannot open content descriptors at all.
 */
interface MediaResourceResolver {
    suspend fun resolve(item: MediaItem): ResolvedMediaSource

    class MediaOpenException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Pure JVM / direct path resolver that resolves non-content targets directly and fails loudly
     * on `content://` URIs when no Android ContentResolver is present.
     */
    object Direct : MediaResourceResolver {
        override suspend fun resolve(item: MediaItem): ResolvedMediaSource {
            val rawTarget = item.target.ifBlank { item.uri?.toString().orEmpty() }
            if (rawTarget.startsWith("content://") || item.uri?.scheme == ContentResolver.SCHEME_CONTENT) {
                throw MediaOpenException("Cannot open a descriptor for $rawTarget without ContentResolver")
            }
            val resolvedPath = item.uri?.path
                ?: if (rawTarget.startsWith("file://")) rawTarget.removePrefix("file://") else rawTarget
            return ResolvedMediaSource(resolvedPath)
        }
    }
}

/**
 * Resolves off the main thread; content URIs are opened here and the descriptor's ownership travels
 * with [ResolvedMediaSource], so a load that is cancelled or replaced before `loadfile` releases it
 * instead of leaking it. Every other scheme is handed over as a path: mpv opens those itself.
 */
class DefaultMediaResourceResolver(
    private val context: Context? = null
) : MediaResourceResolver {

    companion object {
        private const val TAG = "MediaResourceResolver"
    }

    override suspend fun resolve(item: MediaItem): ResolvedMediaSource = withContext(Dispatchers.IO) {
        val uri = item.uri
        val rawTarget = item.target.ifBlank { uri?.toString().orEmpty() }
        val isContent = uri?.scheme == ContentResolver.SCHEME_CONTENT || rawTarget.startsWith("content://")
        if (isContent) {
            val descriptor = try {
                if (uri != null && context != null) {
                    context.contentResolver?.openFileDescriptor(uri, "r")
                } else {
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                EngineLog.w(TAG, "cannot open a descriptor for $rawTarget", t)
                null
            } ?: throw MediaResourceResolver.MediaOpenException("Cannot open a descriptor for $rawTarget")

            ResolvedMediaSource(rawTarget, descriptor)
        } else {
            val path = uri?.path
                ?: if (rawTarget.startsWith("file://")) rawTarget.removePrefix("file://") else rawTarget
            ResolvedMediaSource(path)
        }
    }
}
