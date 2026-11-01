package dev.gyro.feature.player

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class PowerMetrics(
    val powerWatts: Double = 0.0,
    val currentMa: Int = 0,
    val voltageV: Double = 0.0,
    val temperatureC: Double = 0.0,
    val isEstimated: Boolean = false
)

/**
 * Real-time battery power & thermal monitor for Snapdragon 8 Elite / OnePlus 13.
 */
class PowerMonitor(private val context: Context) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val _powerMetrics = MutableStateFlow(PowerMetrics())
    val powerMetrics: StateFlow<PowerMetrics> = _powerMetrics.asStateFlow()

    fun startMonitoring(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val metrics = samplePower()
                _powerMetrics.value = metrics
                delay(1000L)
            }
        }
    }

    private fun samplePower(): PowerMetrics {
        val bm = batteryManager ?: return PowerMetrics()

        // 1. Current in microamperes (uA)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val measuredMa = (abs(currentUa) / 1000).coerceAtMost(10000)

        // 2. Voltage & Temperature via sticky battery broadcast
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4100) ?: 4100
        val voltageV = voltageMv / 1000.0
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val temperatureC = tempRaw / 10.0

        // 3. Power calculation or Snapdragon 8 Elite hardware decode model fallback
        // When plugged into USB or blocked by OEM HAL (returning 0), fallback to accurate hardware estimation
        val isBlockedOrPlugged = measuredMa <= 0
        val finalWatts = if (isBlockedOrPlugged) {
            // Snapdragon 8 Elite 1080p HEVC hardware decode estimated base power
            1.05
        } else {
            (abs(currentUa.toDouble()) * voltageMv.toDouble()) / 1_000_000_000.0
        }

        return PowerMetrics(
            powerWatts = finalWatts,
            currentMa = if (isBlockedOrPlugged) (finalWatts * 1000.0 / voltageV).toInt() else measuredMa,
            voltageV = voltageV,
            temperatureC = temperatureC,
            isEstimated = isBlockedOrPlugged
        )
    }
}
