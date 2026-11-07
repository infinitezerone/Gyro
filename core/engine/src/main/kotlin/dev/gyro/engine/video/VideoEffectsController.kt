package dev.gyro.engine.video

import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Manages video post-processing adjustments (deband, brightness, contrast, saturation).
 */
class VideoEffectsController(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem {

    private val _deband = store.property({ it.deband }) { s, v -> s.copy(deband = v) }
    val deband: StateFlow<Boolean> = _deband.asStateFlow()

    private val _brightness = store.property({ it.brightness }) { s, v -> s.copy(brightness = v) }
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _contrast = store.property({ it.contrast }) { s, v -> s.copy(contrast = v) }
    val contrast: StateFlow<Int> = _contrast.asStateFlow()

    private val _saturation = store.property({ it.saturation }) { s, v -> s.copy(saturation = v) }
    val saturation: StateFlow<Int> = _saturation.asStateFlow()

    override fun registerObservers() {
        bridge.observeProperty("deband", MpvNativeBridge.Format.FLAG)
        bridge.observeProperty("brightness", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("contrast", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("saturation", MpvNativeBridge.Format.INT64)
    }

    override fun onEvent(event: MpvEvent) {
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

    override fun reset() {
        resetColors()
    }

    fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect(::onEvent)
        }

    fun setDeband(enabled: Boolean) {
        _deband.value = enabled
        bridge.setPropertyBoolean("deband", enabled)
    }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _brightness.value = clamped
        bridge.setPropertyLong("brightness", clamped.toLong())
    }

    fun setContrast(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _contrast.value = clamped
        bridge.setPropertyLong("contrast", clamped.toLong())
    }

    fun setSaturation(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _saturation.value = clamped
        bridge.setPropertyLong("saturation", clamped.toLong())
    }

    fun resetColors() {
        setBrightness(0)
        setContrast(0)
        setSaturation(0)
    }

    fun onDebandPropertyChanged(enabled: Boolean) {
        _deband.value = enabled
    }

    fun onColorPropertyChanged(name: String, value: Int) {
        val clamped = value.coerceIn(-100, 100)
        when (name) {
            "brightness" -> _brightness.value = clamped
            "contrast" -> _contrast.value = clamped
            "saturation" -> _saturation.value = clamped
        }
    }
}
