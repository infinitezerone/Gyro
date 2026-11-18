package dev.gyro.feature.player.event

import android.net.Uri
import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.model.HwdecMode

typealias PlaybackTriggerSource = dev.gyro.core.domain.model.PlaybackTriggerSource
typealias PlaybackFailureReason = dev.gyro.core.domain.model.PlaybackFailureReason

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

internal fun PlaybackSessionEvent.toPlayerEvent(): PlayerEvent = when (this) {
    is PlaybackSessionEvent.SwitchInitiated -> PlayerEvent.SwitchInitiated(
        fromIndex = fromIndex,
        toIndex = toIndex,
        source = source,
    )
    is PlaybackSessionEvent.SwitchCompleted -> PlayerEvent.SwitchCompleted(
        index = index,
        uri = uri,
    )
    is PlaybackSessionEvent.HwdecAutoFallback -> PlayerEvent.HwdecAutoFallback(
        from = from,
        to = to,
    )
    is PlaybackSessionEvent.ErrorOccurred -> PlayerEvent.ErrorOccurred(
        reason = reason,
    )
}
