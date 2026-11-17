package dev.gyro.feature.player.event

import android.net.Uri

/** Source of a user-initiated or feed-driven item switch on the player screen. */
enum class PlaybackTriggerSource {
    USER_PLAYLIST_CLICK,
    SHORT_VIDEO_SWIPE,
}

/** Screen-level switch lifecycle events emitted by [dev.gyro.feature.player.PlayerViewModel]. */
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
}
