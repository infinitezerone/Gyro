package dev.gyro.core.model

/**
 * Snapshot of device battery power & thermal telemetry for diagnostic display.
 */
data class PowerMetrics(
    val powerWatts: Double = 0.0,
    val currentMa: Int = 0,
    val voltageV: Double = 0.0,
    val temperatureC: Double = 0.0,
    val isEstimated: Boolean = false,
)
