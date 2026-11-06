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
 */
interface PlaybackHistoryRepository {
    /** Cold view of the persisted history. Collecting is the caller's decision, not ours. */
    val progressMap: Flow<Map<String, PlaybackProgress>>

    /**
     * Persists a resume point. Fire-and-forget on purpose: the player calls this from
     * `onCleared`, where no caller-owned coroutine survives, so the write has to be owned by the
     * repository scope rather than by the call site.
     *
     * Rate limiting is deliberately absent: "how often to save" is a session policy owned by the
     * caller, and keeping its state here would leak across ViewModel lifetimes.
     */
    fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String = "")

    suspend fun getProgress(uri: Uri): PlaybackProgress?
    fun clearAll()
    fun clearHistory()
    fun clearProgress(uri: Uri)
    fun deleteEntry(uri: Uri)
}

/**
 * Room is the single source of truth: [progressMap] is the DAO flow mapped and shared, with no
 * in-memory copy to fall out of sync. Writes go straight to the DAO on the repository scope.
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

    override fun clearAll() {
        appScope.launch { dao.clearAll() }
    }

    override fun clearHistory() = clearAll()

    override fun clearProgress(uri: Uri) = deleteEntry(uri)

    override fun deleteEntry(uri: Uri) {
        val uriStr = uri.toString()
        appScope.launch { dao.deleteByUri(uriStr) }
    }

    private companion object {
        const val MAX_ENTRIES = 500

        /** Shorter positions are noise: users scrub the first seconds constantly. */
        const val MIN_RESUME_POSITION_MS = 2_000L
    }
}

/**
 * Test-only construction path: callers supply their own DAO so the repository can be exercised
 * without Hilt.
 */
fun PlaybackHistoryRepository(
    dao: PlaybackHistoryDao,
    appScope: CoroutineScope,
): PlaybackHistoryRepository = DefaultPlaybackHistoryRepository(dao, appScope)
