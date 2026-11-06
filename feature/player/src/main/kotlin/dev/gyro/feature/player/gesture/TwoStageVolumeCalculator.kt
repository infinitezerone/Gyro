package dev.gyro.feature.player.gesture

import kotlin.math.roundToInt

/**
 * Pure mathematical coordinator for 2-stage vertical volume adjustments:
 * - Stage 1 (0%..100%): Scales Android system stream volume (0..maxSystemVolume); software volume is 100.0%.
 * - Stage 2 (101%..200%): Clamps system volume to maxSystemVolume (isolated); scales software gain (101.0..200.0%).
 */
object TwoStageVolumeCalculator {

    data class VolumeResult(
        val totalPercent: Int,
        val systemVolume: Int,
        val softwareVolume: Double,
        val volumeRatio: Float,
        val isOverdrive: Boolean
    )

    /**
     * Calculates the starting percentage (0..200) from current hardware & software state.
     */
    fun computeInitialPercent(
        currentSystemVolume: Int,
        maxSystemVolume: Int,
        currentSoftwareVolume: Double
    ): Int {
        val safeMax = maxSystemVolume.coerceAtLeast(1)
        val sysRatio = (currentSystemVolume.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
        val sysPct = (sysRatio * 100f).roundToInt()
        val safeSoft = if (currentSoftwareVolume.isNaN()) 100.0 else currentSoftwareVolume
        return if (currentSystemVolume < safeMax) {
            sysPct
        } else {
            100 + (safeSoft - 100.0).roundToInt().coerceIn(0, 100)
        }
    }

    /**
     * Calculates target system volume, software gain, ratio, and overdrive state for a given gesture delta.
     */
    fun calculate(
        dragStartPercent: Int,
        deltaRatio: Float,
        maxSystemVolume: Int
    ): VolumeResult {
        val safeMax = maxSystemVolume.coerceAtLeast(1)
        val deltaPct = when {
            deltaRatio.isNaN() -> 0
            deltaRatio.isInfinite() -> if (deltaRatio > 0f) 200 else -200
            else -> (deltaRatio * 100f).roundToInt()
        }
        val targetPct = (dragStartPercent + deltaPct).coerceIn(0, 200)
        val isOverdrive = targetPct > 100

        val targetSysVol = if (isOverdrive) {
            safeMax
        } else {
            (targetPct / 100f * safeMax).roundToInt().coerceIn(0, safeMax)
        }

        val softVol = if (isOverdrive) {
            targetPct.toDouble()
        } else {
            100.0
        }

        val ratio = if (isOverdrive) {
            (targetPct - 100) / 100f
        } else {
            targetPct / 100f
        }

        return VolumeResult(
            totalPercent = targetPct,
            systemVolume = targetSysVol,
            softwareVolume = softVol,
            volumeRatio = ratio,
            isOverdrive = isOverdrive
        )
    }
}
