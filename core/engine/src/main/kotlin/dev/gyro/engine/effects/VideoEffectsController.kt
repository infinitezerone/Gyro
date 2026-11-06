package dev.gyro.engine.effects

import dev.gyro.engine.MpvNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages video post-processing adjustments (deband, brightness, contrast, saturation).
 */
interface VideoEffectsController {
    val deband: StateFlow<Boolean>
    val brightness: StateFlow<Int>
    val contrast: StateFlow<Int>
    val saturation: StateFlow<Int>

    fun setDeband(enabled: Boolean)
    fun setBrightness(value: Int)
    fun setContrast(value: Int)
    fun setSaturation(value: Int)
    fun resetColors()

    fun onDebandPropertyChanged(enabled: Boolean)
    fun onColorPropertyChanged(name: String, value: Int)
}

class RealVideoEffectsController(
    private val bridge: MpvNativeBridge
) : VideoEffectsController {

    private val _deband = MutableStateFlow(false)
    override val deband: StateFlow<Boolean> = _deband.asStateFlow()

    private val _brightness = MutableStateFlow(0)
    override val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(0)
    override val contrast: StateFlow<Int> = _contrast.asStateFlow()

    private val _saturation = MutableStateFlow(0)
    override val saturation: StateFlow<Int> = _saturation.asStateFlow()

    override fun setDeband(enabled: Boolean) {
        _deband.value = enabled
        bridge.setPropertyBoolean("deband", enabled)
    }

    override fun setBrightness(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _brightness.value = clamped
        bridge.setPropertyLong("brightness", clamped.toLong())
    }

    override fun setContrast(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _contrast.value = clamped
        bridge.setPropertyLong("contrast", clamped.toLong())
    }

    override fun setSaturation(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _saturation.value = clamped
        bridge.setPropertyLong("saturation", clamped.toLong())
    }

    override fun resetColors() {
        setBrightness(0)
        setContrast(0)
        setSaturation(0)
    }

    override fun onDebandPropertyChanged(enabled: Boolean) {
        _deband.value = enabled
    }

    override fun onColorPropertyChanged(name: String, value: Int) {
        val clamped = value.coerceIn(-100, 100)
        when (name) {
            "brightness" -> _brightness.value = clamped
            "contrast" -> _contrast.value = clamped
            "saturation" -> _saturation.value = clamped
        }
    }
}
