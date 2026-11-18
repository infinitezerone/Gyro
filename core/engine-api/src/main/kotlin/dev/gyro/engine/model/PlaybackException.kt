package dev.gyro.engine.model

import java.io.IOException

/**
 * Thrown when a media URI or file descriptor cannot be opened prior to demuxing
 * (e.g. missing file or revoked SAF/ContentProvider permission).
 */
open class PlaybackSourceOpenException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Published when the underlying playback engine fails while loading, demuxing, or decoding media.
 */
open class EnginePlaybackException(
    val reason: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
