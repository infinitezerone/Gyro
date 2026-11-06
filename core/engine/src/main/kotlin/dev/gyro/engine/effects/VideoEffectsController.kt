package dev.gyro.engine.effects

import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages video post-processing adjustments (deband, brightness, contrast, saturation).
 */
interface VideoEffectsController {
    val deband: StateFlow<Boolean>
    val brightness: StateFlow<Int>
    val contrast: StateFlow<Int>
    val saturation: StateFlow<Int>

    fun observeEvents(scope: CoroutineScope): Job
    fun setDeband(enabled: Boolean)
    fun setBrightness(value: Int)
    fun setContrast(value: Int)
    fun setSaturation(value: Int)
    fun resetColors()

    fun onDebandPropertyChanged(enabled: Boolean)
    fun onColorPropertyChanged(name: String, value: Int)
}

@Singleton
class RealVideoEffectsController @Inject constructor(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : VideoEffectsController {

    private val _deband = store.property({ it.deband }) { s, v -> s.copy(deband = v) }
    override val deband: StateFlow<Boolean> = _deband.asStateFlow()

    private val _brightness = store.property({ it.brightness }) { s, v -> s.copy(brightness = v) }
    override val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _contrast = store.property({ it.contrast }) { s, v -> s.copy(contrast = v) }
    override val contrast: StateFlow<Int> = _contrast.asStateFlow()

    private val _saturation = store.property({ it.saturation }) { s, v -> s.copy(saturation = v) }
    override val saturation: StateFlow<Int> = _saturation.asStateFlow()

    override fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect { event ->
                when (event) {
                    is MpvEvent.FlagProperty -> {
                        if (event.name == "deband") onDebandPropertyChanged(event.value)
                    }
                    is MpvEvent.LongProperty -> when (event.name) {
                        "brightness", "contrast", "saturation" ->
                            onColorPropertyChanged(event.name, event.value.toInt())
                    }
                    else -> Unit
                }
            }
        }

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
