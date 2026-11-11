package dev.gyro.engine.api.controller

import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for video post-processing adjustments (deband, brightness, contrast, saturation, tone mapping).
 */
interface VideoController {
    val deband: StateFlow<Boolean>
    val brightness: StateFlow<Int>
    val contrast: StateFlow<Int>
    val saturation: StateFlow<Int>

    fun setDeband(enabled: Boolean)
    fun setToneMapping(algorithm: String)
    fun setBrightness(value: Int)
    fun setContrast(value: Int)
    fun setSaturation(value: Int)
    fun resetColors()
}
