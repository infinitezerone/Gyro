package dev.gyro.core.data.repository

import android.net.TestUri
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlaybackHistoryRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun saveProgress_belowMinimumPosition_deletesExistingEntry() = runTest(testDispatcher) {
        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        try {
            val dao = FakePlaybackHistoryDao()
            val repo = DefaultPlaybackHistoryRepository(dao, repoScope)
            val uri = TestUri("content://media/external/video/media/42")

            repo.saveProgress(uri, positionMs = 45_000L, durationMs = 120_000L, title = "Episode 1")
            advanceUntilIdle()
            assertEquals(45_000L, repo.getProgress(uri)?.positionMs)

            // Rewinding to near 00:00 must clear the stale progress instead of leaving 45s behind
            repo.saveProgress(uri, positionMs = 500L, durationMs = 120_000L, title = "Episode 1")
            advanceUntilIdle()
            assertNull(repo.getProgress(uri))
        } finally {
            repoScope.cancel()
        }
    }

    @Test
    fun saveProgress_rapidCalls_areSerializedAndDoNotReorder() = runTest(testDispatcher) {
        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        try {
            var upsertCount = 0
            var deleteCount = 0
            val delayedDao = object : PlaybackHistoryDao {
                private val delegate = FakePlaybackHistoryDao()

                override fun observeRecentHistory(limit: Int) = delegate.observeRecentHistory(limit)
                override fun observeAllHistory() = delegate.observeAllHistory()
                override suspend fun getByUri(uriString: String) = delegate.getByUri(uriString)
                override suspend fun getAllHistory() = delegate.getAllHistory()
                override suspend fun upsert(history: PlaybackHistoryEntity) {
                    upsertCount++
                    // Delay odd writes so an unserialized second write/delete would finish first and be clobbered
                    if (upsertCount % 2 == 1) delay(200)
                    delegate.upsert(history)
                }
                override suspend fun upsertAll(histories: List<PlaybackHistoryEntity>) = delegate.upsertAll(histories)
                override suspend fun deleteByUri(uriString: String) {
                    deleteCount++
                    delegate.deleteByUri(uriString)
                }
                override suspend fun clearAll() = delegate.clearAll()
                override suspend fun trimOldHistory(keepCount: Int) = delegate.trimOldHistory(keepCount)
            }

            val repo = DefaultPlaybackHistoryRepository(delayedDao, repoScope)
            val uri = TestUri("content://media/external/video/media/7")

            repo.saveProgress(uri, positionMs = 30_000L, durationMs = 100_000L, title = "Movie")
            repo.saveProgress(uri, positionMs = 10_000L, durationMs = 100_000L, title = "Movie")
            advanceUntilIdle()

            assertEquals(2, upsertCount)
            assertEquals(10_000L, repo.getProgress(uri)?.positionMs)

            repo.saveProgress(uri, positionMs = 40_000L, durationMs = 100_000L, title = "Movie")
            repo.saveProgress(uri, positionMs = 500L, durationMs = 100_000L, title = "Movie")
            advanceUntilIdle()

            assertEquals(3, upsertCount)
            assertEquals(1, deleteCount)
            assertNull(repo.getProgress(uri))
        } finally {
            repoScope.cancel()
        }
    }

    @Test
    fun progressMap_suppressesDuplicateEmissions() = runTest(testDispatcher) {
        val repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val rawFlow = MutableStateFlow<List<PlaybackHistoryEntity>>(emptyList())
        val dao = object : PlaybackHistoryDao by FakePlaybackHistoryDao() {
            override fun observeRecentHistory(limit: Int): Flow<List<PlaybackHistoryEntity>> = rawFlow
        }
        val repo = DefaultPlaybackHistoryRepository(dao, repoScope)
        val emissions = mutableListOf<Map<String, PlaybackProgress>>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.progressMap.collect { emissions.add(it) }
        }
        try {
            advanceUntilIdle()

            val entity = PlaybackHistoryEntity(
                uriString = "content://media/1",
                positionMs = 10_000L,
                durationMs = 60_000L,
                updatedAt = 1_000L,
                title = "Title A",
            )
            rawFlow.value = listOf(entity)
            advanceUntilIdle()
            // Same mapped PlaybackProgress even though title changed (title is not in PlaybackProgress)
            rawFlow.value = listOf(entity.copy(title = "Title B"))
            advanceUntilIdle()

            assertEquals(2, emissions.size)
        } finally {
            job.cancel()
            repoScope.cancel()
        }
    }
}
