package dev.gyro.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.gyro.engine.model.PlaybackDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * UI state holder for the live bitrate readout.
 *
 * Sampling only runs while someone is looking: the caller passes [isActive] (stats overlay open or
 * the stats/settings sheet showing), and the loop is cancelled the moment it goes false.
 * Bitrate updates arrive reactively from [diagnosticsFlow] (property-observed inside the engine
 * without polling on UI frames).
 */
@Stable
class PlayerTelemetryState(private val diagnosticsFlow: StateFlow<PlaybackDiagnostics>) {

    var liveVideoBitrate by mutableLongStateOf(0L)
        private set

    var liveAudioBitrate by mutableLongStateOf(0L)
        private set

    /** Newest sample last, bounded so a long session cannot grow it. */
    val bitrateHistory = mutableStateListOf<Long>()

    /** One sampling pass reading the engine-reported diagnostics snapshot. */
    fun sample() {
        val reported = diagnosticsFlow.value
        val videoSample = reported.videoBitrate
        if (videoSample > 0L) {
            liveVideoBitrate = videoSample
            bitrateHistory.add(videoSample)
            if (bitrateHistory.size > MAX_SAMPLES) {
                bitrateHistory.removeAt(0)
            }
        }
        liveAudioBitrate = reported.audioBitrate
    }

    private companion object {
        const val MAX_SAMPLES = 50
    }
}

@Composable
fun rememberPlayerTelemetryState(
    diagnosticsFlow: StateFlow<PlaybackDiagnostics>,
    isActive: Boolean,
): PlayerTelemetryState {
    val state = remember(diagnosticsFlow) { PlayerTelemetryState(diagnosticsFlow) }
    LaunchedEffect(diagnosticsFlow, isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            state.sample()
            delay(SAMPLE_INTERVAL_MS)
        }
    }
    return state
}

private const val SAMPLE_INTERVAL_MS = 1_000L
