package dev.gyro.engine.media

import android.os.ParcelFileDescriptor

/**
 * A target mpv can load, plus what must be released if the hand-off never happens.
 *
 * Ownership: the descriptor stays on this side until [takeTargetUrl] runs. mpv's `fdclose://`
 * takes the descriptor at that moment and closes it when the file is unloaded, so [close] must not
 * touch it afterwards - closing an fd twice is worse than leaking one, because by then the number
 * can belong to an unrelated file.
 */
class ResolvedMediaSource(
    private val targetPathOrUri: String,
    private val descriptor: ParcelFileDescriptor? = null
) : AutoCloseable {

    /** Target as resolved, before any descriptor is detached. Safe to log. */
    val targetDescription: String = targetPathOrUri

    private var released = false
    private var detachedUrl: String? = null

    /**
     * The URL to hand to `loadfile`. Detaches the descriptor on the first call; every later call
     * returns the same URL instead of detaching a second time.
     */
    @Synchronized
    fun takeTargetUrl(): String {
        detachedUrl?.let { return it }
        // Closed without a hand-off: the descriptor is gone, only the path is left to try.
        if (released) return targetPathOrUri
        val pending = descriptor ?: return targetPathOrUri
        val url = "fdclose://${pending.detachFd()}"
        detachedUrl = url
        released = true
        return url
    }

    /** Releases the descriptor when the load never reached mpv. Idempotent. */
    @Synchronized
    override fun close() {
        if (released) return
        released = true
        descriptor?.close()
    }
}
