package dev.gyro.engine.api.controller

import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for subtitle and audio delay calibration, subtitle scale, and subtitle position.
 */
interface TuningController {
    val subtitleDelaySec: StateFlow<Double>
    val audioDelaySec: StateFlow<Double>
    val subtitleScale: StateFlow<Double>
    val subtitlePositionPercent: StateFlow<Long>
    val subAssOverride: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun setSubtitleDelay(delaySec: Double)
    fun setAudioDelay(delaySec: Double)
    fun setSubtitleScale(scale: Double)
    fun setSubtitlePosition(percent: Long)
    fun setSubtitlePosition(percent: Int) = setSubtitlePosition(percent.toLong())
    fun setSubAssOverride(force: Boolean) {}
    fun resetDelays()
}
