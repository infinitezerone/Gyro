package dev.gyro.feature.player.domain

import android.net.Uri

/** 播放切换的意图来源 */
enum class PlaybackTriggerSource {
    USER_PLAYLIST_CLICK,
    SHORT_VIDEO_SWIPE,
    AUTO_PLAY_NEXT,
    SYSTEM_INTENT,
    RESTORE_FROM_BACKGROUND
}

/** 播放故障分类 */
sealed interface PlaybackFailureReason {
    data class DemuxError(val message: String) : PlaybackFailureReason
    data class CodecCrash(val codecName: String) : PlaybackFailureReason
    data class FileNotFound(val uri: Uri) : PlaybackFailureReason
    data class PermissionDenied(val uri: Uri) : PlaybackFailureReason
    data object Unknown : PlaybackFailureReason
}

/** 供上层/UI 订阅的轻量领域事件（不承载逐帧高频数据） */
sealed interface PlayerDomainEvent {
    data class SwitchInitiated(val fromIndex: Int, val toIndex: Int, val source: PlaybackTriggerSource) : PlayerDomainEvent
    data class SwitchCompleted(val index: Int, val uri: Uri) : PlayerDomainEvent
    data class HwdecAutoFallback(val from: String, val to: String) : PlayerDomainEvent
    data class ErrorOccurred(val reason: PlaybackFailureReason) : PlayerDomainEvent
}
