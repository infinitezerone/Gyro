package dev.gyro.core.model

import java.util.Locale

/**
 * Represents a video chapter marker parsed from mpv or media container.
 *
 * @property index 0-based chapter index.
 * @property title Chapter title, or localized fallback (e.g. "Chapter 1").
 * @property timeMs Starting timestamp of the chapter in milliseconds.
 */
data class ChapterInfo(
    val index: Int,
    val title: String,
    val timeMs: Long
) {
    /**
     * Formats the chapter timestamp into standard "MM:SS" or "H:MM:SS" string.
     */
    val formattedTime: String
        get() {
            val totalSec = (timeMs / 1000).coerceAtLeast(0)
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
}
