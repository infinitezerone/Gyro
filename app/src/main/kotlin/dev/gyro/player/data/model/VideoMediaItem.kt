package dev.gyro.player.data.model

import android.net.Uri

/**
 * 本地视频媒体项数据模型
 */
data class VideoMediaItem(
    val id: Long,
    val uri: Uri,
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
     * 格式化时长 (如 05:24 或 01:23:45)
     */
    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
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
                gb >= 1.0 -> String.format("%.1f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.0f KB", kb)
            }
        }

    /**
     * 分辨率等级标识 (如 4K, 1080P, 720P)
     */
    val resolutionBadge: String?
        get() {
            val maxDim = maxOf(width, height)
            return when {
                maxDim >= 3840 -> "4K"
                maxDim >= 2560 -> "2K"
                maxDim >= 1920 -> "1080P"
                maxDim >= 1280 -> "720P"
                maxDim > 0 -> "${maxDim}P"
                else -> null
            }
        }
}
