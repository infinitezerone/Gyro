package dev.gyro.core.data.repository

import android.net.Uri
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import dev.gyro.core.model.PlaybackProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val writeMutex = Mutex()

    override val progressMap: Flow<Map<String, PlaybackProgress>> =
        dao.observeRecentHistory(MAX_ENTRIES)
            .map { entities -> entities.associate { it.uriString to it.toModel() } }
            .distinctUntilChanged()

    override suspend fun getProgress(uri: Uri): PlaybackProgress? =
        dao.getByUri(uri.toString())?.toModel()

    override fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String) {
        if (durationMs <= 0L) return

        val uriStr = uri.toString()
        if (positionMs < MIN_RESUME_POSITION_MS) {
            appScope.launch {
                writeMutex.withLock {
                    dao.deleteByUri(uriStr)
                }
            }
            return
        }

        appScope.launch {
            writeMutex.withLock {
                dao.upsert(
                    PlaybackHistoryEntity(
                        uriString = uriStr,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        updatedAt = System.currentTimeMillis(),
                        title = title,
                    )
                )
                if (writesSinceTrim.incrementAndGet() % TRIM_INTERVAL == 0) {
                    dao.trimOldHistory(MAX_ENTRIES)
                }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val TRIM_INTERVAL = 25
        const val MIN_RESUME_POSITION_MS = 2_000L
    }
}
