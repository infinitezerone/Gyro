package dev.gyro.core.data.repository

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.PowerMetrics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Main-safe implementation of [PowerRepository] reading [BatteryManager] properties and sticky
 * battery status on [GyroDispatchers.IO].
 */
@Singleton
class DefaultPowerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : PowerRepository {

    private val batteryManager by lazy {
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    }

    override suspend fun readPowerMetrics(): PowerMetrics = withContext(ioDispatcher) {
        val bm = batteryManager ?: return@withContext PowerMetrics()

        val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val measuredMa = (abs(currentUa) / 1000).coerceAtMost(10_000)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4100) ?: 4100
        val voltageV = voltageMv / 1000.0
        val tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val temperatureC = tempRaw / 10.0

        val isBlockedOrPlugged = measuredMa <= 0
        val finalWatts = if (isBlockedOrPlugged) {
            1.05
        } else {
            (abs(currentUa.toDouble()) * voltageMv.toDouble()) / 1_000_000_000.0
        }

        PowerMetrics(
            powerWatts = finalWatts,
            currentMa = if (isBlockedOrPlugged) (finalWatts * 1000.0 / voltageV).toInt() else measuredMa,
            voltageV = voltageV,
            temperatureC = temperatureC,
            isEstimated = isBlockedOrPlugged,
        )
    }
}
