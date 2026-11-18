package dev.gyro.engine.session

import dev.gyro.engine.model.PlaybackError
import dev.gyro.engine.model.PlaybackSourceOpenException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Turns failures raised inside the engine into [PlaybackError]s while the context that produced
 * them is still known.
 *
 * This exists so no layer above `:core:engine` has to guess a cause from message text. Everything
 * decided here is based on exception types and the stage the failure came from.
 */
internal object PlaybackErrorClassifier {

    /**
     * A failure while resolving or opening a source. Reaching this means the file was never handed
     * to mpv, so the outcome is always source-related — never a decoder problem.
     */
    fun fromSourceOpenFailure(error: Throwable): PlaybackError {
        val chain = error.causeChain()
        val detail = error.message ?: error::class.java.simpleName
        return when {
            chain.any { it is SecurityException } ->
                PlaybackError.SourcePermissionDenied(detail, error)

            chain.any { it is FileNotFoundException } ->
                PlaybackError.SourceUnreadable(detail, error)

            // PlaybackSourceOpenException covers descriptors that failed to open for reasons the
            // resolver could not narrow down further; it is still a source problem, not a decode one.
            chain.any { it is PlaybackSourceOpenException || it is IOException } ->
                PlaybackError.SourceUnreadable(detail, error)

            else -> PlaybackError.Unknown(detail, error)
        }
    }

    /** mpv terminated the file with `reason=error`. */
    fun fromMpvEndFileError(cause: Throwable): PlaybackError =
        PlaybackError.PlaybackFailed(cause.message ?: "mpv failed to decode or demux the media", cause)

    private fun Throwable.causeChain(): List<Throwable> =
        generateSequence(this) { current ->
            current.cause?.takeIf { it !== current }
        }.toList()
}
