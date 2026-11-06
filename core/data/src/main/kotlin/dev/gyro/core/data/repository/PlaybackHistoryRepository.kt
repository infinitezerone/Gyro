package dev.gyro.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.database.dao.PlaybackHistoryDao
import dev.gyro.core.database.entity.PlaybackHistoryEntity
import dev.gyro.core.model.PlaybackProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance, Room-backed persistent repository for tracking playback progress and resume points.
 *
 * Architecture highlights aligned with Now in Android & BgmPlus:
 * - Backed by Room database in `:core:database` with ACID guarantees.
 * - Reactive in-memory state [progressMap] kept in sync with the Room DAO for zero-latency lookups.
 * - One-time migration from legacy SharedPreferences ("gyro_playback_history") if existing records are found.
 * - Thread-safe, Main-safe with injected [ioDispatcher] & [appScope].
 */
@Singleton
class PlaybackHistoryRepository @Inject constructor(
    private val dao: PlaybackHistoryDao,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext context: Context? = null,
) {
    /**
     * Testing constructor with custom/fake DAO.
     */
    constructor(
        dao: PlaybackHistoryDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        appScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    ) : this(
        dao = dao,
        ioDispatcher = ioDispatcher,
        appScope = appScope,
        context = null,
    )

    private val _progressMap = MutableStateFlow<Map<String, PlaybackProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, PlaybackProgress>> = _progressMap.asStateFlow()

    private var lastSavedTimestamp: Long = 0L
    private var lastSavedPositionMs: Long = 0L
    private var lastSavedUri: String? = null

    init {
        // 1. One-time legacy SharedPreferences migration if present
        if (context != null) {
            migrateLegacyPrefsIfNeeded(context)
        }

        // 2. Continuously observe Room database updates to sync in-memory cache
        appScope.launch(ioDispatcher) {
            dao.observeRecentHistory(500).collectLatest { entities ->
                val map = entities.associate { it.uriString to it.toModel() }
                _progressMap.value = map
            }
        }
    }

    private fun migrateLegacyPrefsIfNeeded(context: Context) {
        val legacyPrefs = context.getSharedPreferences("gyro_playback_history", Context.MODE_PRIVATE)
        val allEntries = legacyPrefs.all
        if (allEntries.isNotEmpty()) {
            appScope.launch(ioDispatcher) {
                val migrated = mutableListOf<PlaybackHistoryEntity>()
                val now = System.currentTimeMillis()
                for ((key, value) in allEntries) {
                    val str = value as? String ?: continue
                    val parts = str.split(",")
                    if (parts.size >= 2) {
                        val pos = parts[0].toLongOrNull() ?: continue
                        val dur = parts[1].toLongOrNull() ?: continue
                        val title = if (parts.size >= 3) parts[2] else ""
                        migrated.add(
                            PlaybackHistoryEntity(
                                uriString = key,
                                positionMs = pos,
                                durationMs = dur,
                                updatedAt = now,
                                title = title
                            )
                        )
                    }
                }
                if (migrated.isNotEmpty()) {
                    dao.upsertAll(migrated)
                }
                legacyPrefs.edit().clear().apply()
            }
        }
    }

    /**
     * Record playback progress for a media URI.
     * Records with duration <= 0 or position < 2000ms are ignored unless clearing.
     */
    fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long, title: String = "") {
        if (durationMs <= 0L) return
        if (positionMs < 2000L) return

        val uriStr = uri.toString()
        val now = System.currentTimeMillis()
        lastSavedUri = uriStr
        lastSavedTimestamp = now
        lastSavedPositionMs = positionMs

        val progress = PlaybackProgress(
            uriString = uriStr,
            positionMs = positionMs,
            durationMs = durationMs,
            lastPlayedTimestamp = now
        )

        _progressMap.update { current ->
            current + (uriStr to progress)
        }

        appScope.launch(ioDispatcher) {
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

    /**
     * Saves progress only if at least [minIntervalMs] has elapsed since the last write AND
     * the playback position has moved by at least [minDeltaMs].
     * Avoids excessive disk I/O while video is playing smoothly.
     */
    fun saveProgressThrottled(
        uri: Uri,
        positionMs: Long,
        durationMs: Long,
        title: String = "",
        minIntervalMs: Long = 10_000L,
        minDeltaMs: Long = 5_000L
    ): Boolean {
        val now = System.currentTimeMillis()
        val key = uri.toString()
        if (key == lastSavedUri) {
            val elapsed = now - lastSavedTimestamp
            val delta = kotlin.math.abs(positionMs - lastSavedPositionMs)
            if (elapsed < minIntervalMs || delta < minDeltaMs) {
                return false
            }
        }
        saveProgress(uri, positionMs, durationMs, title)
        return true
    }

    /**
     * Retrieve the recorded progress for a media URI from in-memory cache.
     * Guaranteed to be synchronous and non-blocking.
     */
    fun getProgress(uri: Uri): PlaybackProgress? {
        return _progressMap.value[uri.toString()]
    }

    /**
     * Clear all playback history from in-memory cache and Room database.
     */
    fun clearAll() {
        _progressMap.value = emptyMap()
        appScope.launch(ioDispatcher) {
            dao.clearAll()
        }
    }

    fun clearHistory() = clearAll()

    /**
     * Clear playback history for a single URI.
     */
    fun clearProgress(uri: Uri) = deleteEntry(uri)

    /**
     * Delete a single entry from history.
     */
    fun deleteEntry(uri: Uri) {
        val uriStr = uri.toString()
        _progressMap.update { current -> current - uriStr }
        appScope.launch(ioDispatcher) {
            dao.deleteByUri(uriStr)
        }
    }
}
