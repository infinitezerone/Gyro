package dev.gyro.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import dev.gyro.core.model.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Ultra-fast, zero-dependency persistent repository for tracking playback progress and resume points.
 */
class PlaybackHistoryRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("gyro_playback_history", Context.MODE_PRIVATE)

    private val _progressMap = MutableStateFlow<Map<String, PlaybackProgress>>(loadAll())
    val progressMap: StateFlow<Map<String, PlaybackProgress>> = _progressMap.asStateFlow()

    private fun loadAll(): Map<String, PlaybackProgress> {
        val result = mutableMapOf<String, PlaybackProgress>()
        for ((key, value) in prefs.all) {
            if (value is String) {
                parseRecord(key, value)?.let { record ->
                    result[key] = record
                }
            }
        }
        return result
    }

    private fun parseRecord(uriString: String, raw: String): PlaybackProgress? {
        val parts = raw.split(":")
        if (parts.size >= 3) {
            val pos = parts[0].toLongOrNull() ?: 0L
            val dur = parts[1].toLongOrNull() ?: 0L
            val ts = parts[2].toLongOrNull() ?: 0L
            return PlaybackProgress(uriString, pos, dur, ts)
        }
        return null
    }

    /**
     * Retrieve the last playback progress for a given media URI.
     */
    fun getProgress(uri: Uri): PlaybackProgress? {
        val key = uri.toString()
        return _progressMap.value[key]
    }

    /**
     * Save playback position and duration.
     * Records with duration <= 0 or position < 2000ms are ignored unless clearing.
     */
    fun saveProgress(uri: Uri, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val key = uri.toString()
        val timestamp = System.currentTimeMillis()

        // If played within the first 2 seconds, do not record resume clutter
        if (positionMs < 2000L) {
            return
        }

        val record = PlaybackProgress(key, positionMs, durationMs, timestamp)
        val raw = "$positionMs:$durationMs:$timestamp"
        prefs.edit().putString(key, raw).apply()

        _progressMap.update { current ->
            current + (key to record)
        }
    }

    /**
     * Clear playback history for a single URI.
     */
    fun clearProgress(uri: Uri) {
        val key = uri.toString()
        prefs.edit().remove(key).apply()
        _progressMap.update { current ->
            current - key
        }
    }

    /**
     * Clear all recorded playback history.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        _progressMap.value = emptyMap()
    }
}
