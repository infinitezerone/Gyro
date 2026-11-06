package dev.gyro.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Query("SELECT * FROM playback_history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 100): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history ORDER BY updatedAt DESC")
    fun observeAllHistory(): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE uriString = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): PlaybackHistoryEntity?

    @Query("SELECT * FROM playback_history")
    suspend fun getAllHistory(): List<PlaybackHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(history: PlaybackHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(histories: List<PlaybackHistoryEntity>)

    @Query("DELETE FROM playback_history WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()

    /**
     * Keep record count within [keepCount] (e.g. 500 records), deleting oldest.
     */
    @Query(
        "DELETE FROM playback_history WHERE uriString NOT IN (SELECT uriString FROM playback_history ORDER BY updatedAt DESC LIMIT :keepCount)"
    )
    suspend fun trimOldHistory(keepCount: Int = 500)
}
