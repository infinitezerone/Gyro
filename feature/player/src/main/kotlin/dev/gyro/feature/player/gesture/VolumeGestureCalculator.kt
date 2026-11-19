package dev.gyro.feature.player.gesture

import kotlin.math.roundToInt

/**
 * Pure mathematical coordinator for the vertical volume gesture.
 *
 * Swipe travel maps 0%..100% onto Android's system media stream. Software gain is deliberately not
 * reachable from gestures: it lives only in the audio sheet, so a swipe can never exceed the
 * device's own volume ceiling.
 */
object VolumeGestureCalculator {

    data class VolumeResult(
        val totalPercent: Int,
        val systemVolume: Int,
        val volumeRatio: Float
    )

    /**
     * Calculates the starting percentage (0..100) from the current system volume.
     */
    fun computeInitialPercent(
        currentSystemVolume: Int,
        maxSystemVolume: Int
    ): Int {
        val safeMax = maxSystemVolume.coerceAtLeast(1)
        val ratio = (currentSystemVolume.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
        return (ratio * 100f).roundToInt()
    }

    /**
     * Calculates the target system volume and ratio for a given gesture delta.
     *
     * The ratio stays fractional instead of being quantised to whole percent so the HUD can follow
     * the finger continuously; only the value handed to AudioManager is rounded to a stream step.
     */
    fun calculate(
        dragStartPercent: Int,
        deltaRatio: Float,
        maxSystemVolume: Int
    ): VolumeResult {
        val safeMax = maxSystemVolume.coerceAtLeast(1)
        val deltaPct = when {
            deltaRatio.isNaN() -> 0f
            deltaRatio.isInfinite() -> if (deltaRatio > 0f) 100f else -100f
            else -> deltaRatio * 100f
        }
        val targetPct = (dragStartPercent + deltaPct).coerceIn(0f, 100f)
        val targetSysVol = (targetPct / 100f * safeMax).roundToInt().coerceIn(0, safeMax)
        return VolumeResult(
            totalPercent = targetPct.roundToInt(),
            systemVolume = targetSysVol,
            volumeRatio = targetPct / 100f
        )
    }
}
