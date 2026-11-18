package dev.gyro.engine.model

/**
 * Why playback failed, classified by the only layer that can know: the engine.
 *
 * Consumers branch on the type. No layer above the engine may inspect message text to infer a
 * cause — mpv's wording is an implementation detail that changes between releases, so matching on
 * it silently misclassifies failures (and, for decoder failures, sends the hwdec fallback down the
 * wrong path).
 *
 * The hierarchy is deliberately as shallow as the available evidence:
 * - The resolve stage knows whether the source was refused or simply could not be opened.
 * - mpv's `end-file` reports `reason=error` with no structured cause, so playback failures are
 *   not split into codec vs demux here. `PlaybackFailed` means "mpv could not decode or demux
 *   this within the active configuration"; the caller's decoder mode is the missing discriminator.
 */
sealed class PlaybackError(
    message: String?,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The source could not be opened: missing file, or an unreadable/revoked descriptor. */
    class SourceUnreadable(
        message: String?,
        cause: Throwable? = null,
    ) : PlaybackError(message, cause)

    /** The source exists but reading it was refused (SAF / ContentProvider permission). */
    class SourcePermissionDenied(
        message: String?,
        cause: Throwable? = null,
    ) : PlaybackError(message, cause)

    /** mpv terminated the file with `reason=error` while decoding or demuxing. */
    class PlaybackFailed(
        message: String?,
        cause: Throwable? = null,
    ) : PlaybackError(message, cause)

    /** The engine could not attribute the failure to a source or a playback stage. */
    class Unknown(
        message: String?,
        cause: Throwable? = null,
    ) : PlaybackError(message, cause)
}
