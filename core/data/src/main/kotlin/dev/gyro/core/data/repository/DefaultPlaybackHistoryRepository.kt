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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [PlaybackHistoryRepository] backed by Room as the SSOT.
 */
@Singleton
class DefaultPlaybackHistoryRepository @Inject constructor(
    private val dao: PlaybackHistoryDao,
    @ApplicationScope private val appScope: CoroutineScope,
) : PlaybackHistoryRepository {

    private val writesSinceTrim = AtomicInteger(0)

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
                    title = title,
                )
            )
            if (writesSinceTrim.incrementAndGet() % TRIM_INTERVAL == 0) {
                dao.trimOldHistory(MAX_ENTRIES)
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val TRIM_INTERVAL = 25
        const val MIN_RESUME_POSITION_MS = 2_000L
    }
}
