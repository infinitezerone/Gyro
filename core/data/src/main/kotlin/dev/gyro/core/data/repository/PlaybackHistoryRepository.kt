package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow

/**
 * Interface contract for tracking playback progress and resume points.
 *
 * Data is backed by Room as the Single Source of Truth (SSOT).
 */
interface PlaybackHistoryRepository {
    /** Cold view of the persisted history. Collecting is the caller's decision, not ours. */
    val progressMap: Flow<Map<String, PlaybackProgress>>

    /**
     * Persists a resume point asynchronously on the application scope so teardown callers do not
     * need an active UI coroutine scope.
     */
    fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String = "")

    suspend fun getProgress(uri: Uri): PlaybackProgress?
}
