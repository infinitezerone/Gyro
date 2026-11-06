package dev.gyro.core.testing.fakes

import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory thread-safe fake implementation of [PlaybackHistoryDao] for fast, deterministic unit testing.
 */
class FakePlaybackHistoryDao : PlaybackHistoryDao {

    private val storage = ConcurrentHashMap<String, PlaybackHistoryEntity>()
    private val _historyFlow = MutableStateFlow<List<PlaybackHistoryEntity>>(emptyList())

    private fun updateFlow() {
        _historyFlow.value = storage.values.sortedByDescending { it.updatedAt }
    }

    override fun observeRecentHistory(limit: Int): Flow<List<PlaybackHistoryEntity>> = _historyFlow.asStateFlow()

    override fun observeAllHistory(): Flow<List<PlaybackHistoryEntity>> = _historyFlow.asStateFlow()

    override suspend fun getByUri(uriString: String): PlaybackHistoryEntity? = storage[uriString]

    override suspend fun getAllHistory(): List<PlaybackHistoryEntity> = storage.values.sortedByDescending { it.updatedAt }

    override suspend fun upsert(history: PlaybackHistoryEntity) {
        storage[history.uriString] = history
        updateFlow()
    }

    override suspend fun upsertAll(histories: List<PlaybackHistoryEntity>) {
        histories.forEach { storage[it.uriString] = it }
        updateFlow()
    }

    override suspend fun deleteByUri(uriString: String) {
        storage.remove(uriString)
        updateFlow()
    }

    override suspend fun clearAll() {
        storage.clear()
        updateFlow()
    }

    override suspend fun trimOldHistory(keepCount: Int) {
        val sorted = storage.values.sortedByDescending { it.updatedAt }
        if (sorted.size > keepCount) {
            sorted.drop(keepCount).forEach { storage.remove(it.uriString) }
            updateFlow()
        }
    }
}
