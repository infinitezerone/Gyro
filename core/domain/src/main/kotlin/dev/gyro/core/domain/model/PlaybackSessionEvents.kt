package dev.gyro.core.domain.model

import android.net.Uri
import dev.gyro.core.model.HwdecMode

/** Source of a user-initiated or feed-driven item switch within a playback session. */
enum class PlaybackTriggerSource {
    USER_PLAYLIST_CLICK,
    SHORT_VIDEO_SWIPE,
    AUTO_PLAY_NEXT,
    SYSTEM_INTENT,
    RESTORE_FROM_BACKGROUND,
}

/** Classified reason for a terminal playback failure surfaced by the session layer. */
sealed interface PlaybackFailureReason {
    data class DemuxError(val message: String) : PlaybackFailureReason
    data class CodecCrash(val codecName: String) : PlaybackFailureReason
    data class FileNotFound(val uri: Uri) : PlaybackFailureReason
    data class PermissionDenied(val uri: Uri) : PlaybackFailureReason
    data object Unknown : PlaybackFailureReason
}

/** Session-level lifecycle, switch, decoder-fallback, and terminal error events. */
sealed interface PlaybackSessionEvent {
    data class SwitchInitiated(
        val fromIndex: Int,
        val toIndex: Int,
        val source: PlaybackTriggerSource,
    ) : PlaybackSessionEvent

    data class SwitchCompleted(
        val index: Int,
        val uri: Uri,
    ) : PlaybackSessionEvent

    data class HwdecAutoFallback(
        val from: HwdecMode,
        val to: HwdecMode,
    ) : PlaybackSessionEvent

    data class ErrorOccurred(
        val reason: PlaybackFailureReason,
    ) : PlaybackSessionEvent
}
