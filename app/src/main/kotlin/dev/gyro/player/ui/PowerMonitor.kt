package dev.gyro.player.ui

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
    val voltageMv: Int = 0
)

/**
 * Real-time battery power & current monitor for Snapdragon 8 Elite / OnePlus 13.
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

        // Current in microamperes (uA)
        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = (abs(currentUa) / 1000).coerceAtMost(10000)

        // Voltage in millivolts (mV) via sticky battery intent
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000

        // P (Watts) = (uA * mV) / 10^9
        val powerWatts = (abs(currentUa.toDouble()) * voltageMv.toDouble()) / 1_000_000_000.0

        return PowerMetrics(
            powerWatts = powerWatts,
            currentMa = currentMa,
            voltageMv = voltageMv
        )
    }
}
