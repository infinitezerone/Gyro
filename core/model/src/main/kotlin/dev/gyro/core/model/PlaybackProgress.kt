package dev.gyro.core.model

/**
 * Represents the persistent playback progress of a media item.
 */
data class PlaybackProgress(
    val uriString: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedTimestamp: Long
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Mark as completed if played more than 95% or within 5 seconds of the end.
     */
    val isCompleted: Boolean
        get() = durationMs > 0L && (positionMs >= durationMs - 5_000L || progressFraction >= 0.95f)
}
