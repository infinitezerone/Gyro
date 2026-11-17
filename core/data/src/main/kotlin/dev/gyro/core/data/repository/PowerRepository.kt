package dev.gyro.core.data.repository

import dev.gyro.core.model.PowerMetrics

/**
 * Repository interface for sampling device battery power & thermal telemetry.
 * Implementations must guarantee main-safety.
 */
interface PowerRepository {
    suspend fun readPowerMetrics(): PowerMetrics
}
