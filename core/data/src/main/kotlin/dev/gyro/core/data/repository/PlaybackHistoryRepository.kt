package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import dev.gyro.core.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Interface contract for tracking playback progress and resume points.
 *
 * Data is backed by Room as the Single Source of Truth (SSOT).
 */
interface PlaybackHistoryRepository {
    /** Cold view of the persisted history. Collecting is the caller's decision, not ours. */
    val progressMap: Flow<Map<String, PlaybackProgress>>

    /**
     * Persists a resume point.
     *
     * Note: This method is non-suspending and fire-and-forget by design: the player calls this from
     * `ViewModel.onCleared()` or lifecycle teardown, where no caller-owned coroutine survives.
     * Therefore, the write is executed on an [ApplicationScope] coroutine.
     */
    fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String = "")

    suspend fun getProgress(uri: Uri): PlaybackProgress?
}

/**
 * Default implementation of [PlaybackHistoryRepository].
 *
 * Room is the single source of truth: [progressMap] is the DAO flow mapped and shared, with no
 * in-memory copy to fall out of sync.
 */
class DefaultPlaybackHistoryRepository @Inject constructor(
    private val dao: PlaybackHistoryDao,
    @ApplicationScope private val appScope: CoroutineScope,
) : PlaybackHistoryRepository {

    override val progressMap: Flow<Map<String, PlaybackProgress>> =
        dao.observeRecentHistory(MAX_ENTRIES)
            .map { entities -> entities.associate { it.uriString to it.toModel() } }

    override suspend fun getProgress(uri: Uri): PlaybackProgress? =
        dao.getByUri(uri.toString())?.toModel()

    override fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String) {
        if (durationMs <= 0L) return
        if (positionMs < MIN_RESUME_POSITION_MS) return

        val uriStr = uri.toString()
        val now = System.currentTimeMillis()

        appScope.launch {
            dao.upsert(
                PlaybackHistoryEntity(
                    uriString = uriStr,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = now,
                    title = title
                )
            )
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500

        /** Shorter positions are noise: users scrub the first seconds constantly. */
        const val MIN_RESUME_POSITION_MS = 2_000L
    }
}
