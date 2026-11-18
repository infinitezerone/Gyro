package dev.gyro.engine.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.model.PlaybackSourceOpenException
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a [MediaItem] into something mpv can load.
 *
 * An interface so a test (or a headless harness without a provider) can answer loads without a
 * package manager: the JVM suite cannot open content descriptors at all.
 */
interface MediaResourceResolver {
    suspend fun resolve(item: MediaItem): ResolvedMediaSource

    class MediaOpenException(
        message: String,
        cause: Throwable? = null,
    ) : PlaybackSourceOpenException(message, cause)

    /**
     * Pure JVM / direct path resolver that resolves non-content targets directly and fails loudly
     * on `content://` URIs when no Android ContentResolver is present.
     */
    object Direct : MediaResourceResolver {
        override suspend fun resolve(item: MediaItem): ResolvedMediaSource {
            val uri = item.uri
            val rawTarget = item.target.ifBlank { uri?.toString().orEmpty() }
            if (isContentTarget(uri, rawTarget)) {
                throw MediaOpenException("Cannot open a descriptor for $rawTarget without ContentResolver")
            }
            return ResolvedMediaSource(resolveNonContentTarget(uri, rawTarget))
        }
    }

    companion object {
        internal fun isContentTarget(uri: Uri?, rawTarget: String): Boolean =
            uri?.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) ||
                rawTarget.startsWith("content://", ignoreCase = true)

        internal fun resolveNonContentTarget(uri: Uri?, rawTarget: String): String {
            if (uri != null) {
                if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
                    uri.path?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                return rawTarget
            }
            return if (rawTarget.startsWith("file://", ignoreCase = true)) {
                rawTarget.substring("file://".length)
            } else {
                rawTarget
            }
        }
    }
}

/**
 * Resolves off the main thread; content URIs are opened here and the descriptor's ownership travels
 * with [ResolvedMediaSource], so a load that is cancelled or replaced before `loadfile` releases it
 * instead of leaking it. Every other scheme is handed over as a path: mpv opens those itself.
 */
class DefaultMediaResourceResolver(
    private val context: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaResourceResolver {

    companion object {
        private const val TAG = "MediaResourceResolver"
    }

    override suspend fun resolve(item: MediaItem): ResolvedMediaSource = withContext(ioDispatcher) {
        val uri = item.uri
        val rawTarget = item.target.ifBlank { uri?.toString().orEmpty() }
        val isContent = MediaResourceResolver.isContentTarget(uri, rawTarget)
        if (isContent) {
            var openFailure: Throwable? = null
            val descriptor = try {
                val effectiveUri = uri ?: Uri.parse(rawTarget)
                if (effectiveUri != null && context != null) {
                    context.contentResolver?.openFileDescriptor(effectiveUri, "r")
                } else {
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                EngineLog.w(TAG, "cannot open a descriptor for $rawTarget", t)
                openFailure = t
                null
            } ?: throw MediaResourceResolver.MediaOpenException(
                "Cannot open a descriptor for $rawTarget",
                openFailure,
            )

            ResolvedMediaSource(rawTarget, descriptor)
        } else {
            ResolvedMediaSource(MediaResourceResolver.resolveNonContentTarget(uri, rawTarget))
        }
    }
}
