package dev.gyro.core.model

import dev.gyro.core.model.time.formatDuration

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
        get() = formatDuration(timeMs)
}
