package dev.gyro.core.model

import java.util.Locale

/**
 * 视频与音频编解码、容器与性能详情
 */
data class MediaInfo(
    val title: String = "",
    val pathOrUri: String = "",
    val containerFormat: String = "",
    val fileSize: Long = 0L,
    val durationMs: Long = 0L,
    val videoCodec: String = "",
    val videoResolution: String = "",
    val videoFps: Double = 0.0,
    val videoBitrate: Long = 0L,
    val audioCodec: String = "",
    val audioChannels: String = "",
    val audioSampleRate: Long = 0L,
    val audioBitrate: Long = 0L,
    val hwdecCurrent: String = "",
    val droppedFrames: Long = 0L
) {
    val formattedFileSize: String
        get() {
            if (fileSize <= 0) return "--"
            val kb = fileSize / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }

    val formattedVideoBitrate: String
        get() = if (videoBitrate > 0) "${videoBitrate / 1000} kbps" else "--"

    val formattedAudioBitrate: String
        get() = if (audioBitrate > 0) "${audioBitrate / 1000} kbps" else "--"

    val formattedSampleRate: String
        get() = if (audioSampleRate > 0) "${audioSampleRate} Hz" else "--"
}
