package dev.gyro.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.gyro.core.model.PowerMetrics
import kotlinx.coroutines.delay

private const val POWER_SAMPLE_INTERVAL_MS = 1_000L

/**
 * On-demand Compose UI state holder for battery power & thermal telemetry.
 *
 * Delegates reading to a main-safe [dev.gyro.core.data.repository.PowerRepository] strictly while
 * [isActive] is true (when the diagnostic sheet is open) and stops immediately when closed.
 */
@Stable
class PlayerPowerState {
    var metrics by mutableStateOf(PowerMetrics())
        internal set
}

@Composable
fun rememberPlayerPowerState(
    isActive: Boolean,
    samplePower: suspend () -> PowerMetrics,
): PlayerPowerState {
    val samplePowerState = rememberUpdatedState(samplePower)
    val state = remember { PlayerPowerState() }
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            state.metrics = samplePowerState.value()
            delay(POWER_SAMPLE_INTERVAL_MS)
        }
    }
    return state
}
