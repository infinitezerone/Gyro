package dev.gyro.core.model

import java.util.Locale

/**
 * 本地视频媒体项数据模型
 */
data class VideoMediaItem(
    val id: Long,
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val folderName: String,
    val mimeType: String? = null
) {
    /**
     * URI 字符串别名，便于直接使用字符串访问
     */
    val uri: String get() = uriString

    /**
     * 格式化时长 (如 05:24 或 01:23:45)
     */
    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }

    /**
     * 格式化文件大小 (如 125.4 MB, 1.2 GB)
     */
    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "--"
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }

    /**
     * 分辨率徽章 (如 4K, 1080P, 720P)
     */
    val resolutionBadge: String?
        get() = when {
            width >= 3840 || height >= 2160 -> "4K"
            width >= 2560 || height >= 1440 -> "2K"
            width >= 1920 || height >= 1080 -> "1080P"
            width >= 1280 || height >= 720 -> "720P"
            width > 0 && height > 0 -> "${height}P"
            else -> null
        }
}
