package dev.gyro.feature.player.event

import android.net.Uri
import dev.gyro.core.model.HwdecMode

/** Source of a user-initiated or feed-driven item switch on the player screen. */
enum class PlaybackTriggerSource {
    USER_PLAYLIST_CLICK,
    SHORT_VIDEO_SWIPE,
    AUTO_PLAY_NEXT,
    SYSTEM_INTENT,
    RESTORE_FROM_BACKGROUND,
}

/** Classified reason for a terminal playback failure surfaced to the player UI. */
sealed interface PlaybackFailureReason {
    data class DemuxError(val message: String) : PlaybackFailureReason
    data class CodecCrash(val codecName: String) : PlaybackFailureReason
    data class FileNotFound(val uri: Uri) : PlaybackFailureReason
    data class PermissionDenied(val uri: Uri) : PlaybackFailureReason
    data object Unknown : PlaybackFailureReason
}

/** Screen-level lifecycle, fallback, and error events emitted by [dev.gyro.feature.player.PlayerViewModel]. */
sealed interface PlayerEvent {
    data class SwitchInitiated(
        val fromIndex: Int,
        val toIndex: Int,
        val source: PlaybackTriggerSource,
    ) : PlayerEvent

    data class SwitchCompleted(
        val index: Int,
        val uri: Uri,
    ) : PlayerEvent

    data class HwdecAutoFallback(
        val from: HwdecMode,
        val to: HwdecMode,
    ) : PlayerEvent

    data class ErrorOccurred(
        val reason: PlaybackFailureReason,
    ) : PlayerEvent
}
