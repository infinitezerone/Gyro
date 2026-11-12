package dev.gyro.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.gyro.engine.api.PlayerEngine
import kotlinx.coroutines.delay

/**
 * UI state holder for the live bitrate readout.
 *
 * The sampling loop used to live in `PlayerScreen` as a bare `LaunchedEffect` over three
 * `remember`s. Hoisting it here keeps the screen free of timer bookkeeping and makes the cache
 * policy (50 samples, 500ms cadence) a property of the thing that owns it.
 *
 * Sampling only runs while someone is looking: the caller passes [isActive] (stats overlay open or
 * the stats/settings sheet showing), and the loop is cancelled the moment it goes false.
 */
@Stable
class PlayerTelemetryState(private val engine: PlayerEngine) {

    var liveVideoBitrate by mutableLongStateOf(0L)
        private set

    var liveAudioBitrate by mutableLongStateOf(0L)
        private set

    /** Newest sample last, bounded so a long session cannot grow it. */
    val bitrateHistory = mutableStateListOf<Long>()

    /** One sampling pass. Never blocks the caller: it only reads properties and updates state. */
    suspend fun sample() {
        val (video, audio) = engine.queryLiveBitrate()
        val reported = engine.diagnostics.value
        val videoSample = if (video > 0L) video else reported.videoBitrate
        if (videoSample > 0L) {
            liveVideoBitrate = videoSample
            bitrateHistory.add(videoSample)
            if (bitrateHistory.size > MAX_SAMPLES) {
                bitrateHistory.removeAt(0)
            }
        }
        liveAudioBitrate = if (audio > 0L) audio else reported.audioBitrate
    }

    private companion object {
        const val MAX_SAMPLES = 50
    }
}

@Composable
fun rememberPlayerTelemetryState(
    engine: PlayerEngine,
    isActive: Boolean,
): PlayerTelemetryState {
    val state = remember(engine) { PlayerTelemetryState(engine) }
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            state.sample()
            delay(SAMPLE_INTERVAL_MS)
        }
    }
    return state
}

private const val SAMPLE_INTERVAL_MS = 500L
