package dev.gyro.core.model

import android.net.Uri
import java.util.Locale

/**
 * 用户授权的自定义外部目录详情
 */
data class CustomFolderDetail(
    val uri: Uri,
    val displayName: String,
    val videoCount: Int,
    val totalSizeBytes: Long
) {
    val formattedTotalSize: String
        get() {
            if (totalSizeBytes <= 0) return "0 B"
            val kb = totalSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }
}
