package dev.gyro.core.model.time

import java.util.Locale

/**
 * 将毫秒格式化为 "MM:SS" 或 "H:MM:SS"（[padHours] 为 true 时小时固定两位，如 "01:23:45"）。
 *
 * 毫秒按四舍五入取整到秒，负值按 0 处理。全项目唯一的时长格式化实现。
 */
fun formatDuration(ms: Long, padHours: Boolean = false): String {
    val totalSeconds = ((ms + 500L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        val hourFormat = if (padHours) "%02d" else "%d"
        String.format(Locale.US, "$hourFormat:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
