package dev.gyro.core.database.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import dev.gyro.core.model.PlaybackProgress

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey val uriString: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val title: String = ""
) {
    fun toModel(): PlaybackProgress = PlaybackProgress(
        uriString = uriString,
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedTimestamp = updatedAt
    )

    companion object {
        fun fromModel(progress: PlaybackProgress, title: String = ""): PlaybackHistoryEntity =
            PlaybackHistoryEntity(
                uriString = progress.uriString,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                updatedAt = progress.lastPlayedTimestamp,
                title = title
            )
    }
}
