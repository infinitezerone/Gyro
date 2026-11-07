package dev.gyro.engine.audio

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
 * Manages audio filtering, 0-200% volume overdrive, anti-clipping limiter,
 * speech normalization (night mode), and stereo downmixing.
 */
class AudioEffectsController(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem {

    companion object {
        const val FILTER_STEREO_DOWNMIX =
            "lavfi=[pan=stereo|c0=0.5*c2+0.707*c0+0.707*c4|c1=0.5*c2+0.707*c1+0.707*c5]"
        const val FILTER_DYNAUDNORM =
            "lavfi=[dynaudnorm=f=200:g=15:m=10:r=0.9]"
        const val FILTER_ALIMITER =
            "lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]"
    }

    private val _softwareVolume = store.property({ it.softwareVolume }) { s, v -> s.copy(softwareVolume = v) }
    val softwareVolume: StateFlow<Double> = _softwareVolume.asStateFlow()

    private val _nightMode = store.property({ it.nightMode }) { s, v -> s.copy(nightMode = v) }
    val nightMode: StateFlow<Boolean> = _nightMode.asStateFlow()

    private val _stereoDownmix = store.property({ it.stereoDownmix }) { s, v -> s.copy(stereoDownmix = v) }
    val stereoDownmix: StateFlow<Boolean> = _stereoDownmix.asStateFlow()

    override fun registerObservers() {}

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN -> resetSoftwareGain()
            }
            is MpvEvent.EndFile -> when (event.reason) {
                MpvNativeBridge.EndReason.EOF,
                MpvNativeBridge.EndReason.QUIT,
                MpvNativeBridge.EndReason.ERROR -> resetSoftwareGain()
            }
            else -> Unit
        }
    }

    override fun reset() {
        resetSoftwareGain()
    }

    fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect(::onEvent)
        }

    @Synchronized
    fun setSoftwareVolume(gain: Double) {
        val clamped = gain.coerceIn(100.0, 200.0)
        _softwareVolume.value = clamped
        bridge.setPropertyDouble("volume", clamped)
        updateAudioFilters()
    }

    @Synchronized
    fun resetSoftwareGain() {
        _softwareVolume.value = 100.0
        bridge.setPropertyDouble("volume", 100.0)
        updateAudioFilters()
    }

    @Synchronized
    fun setNightMode(enabled: Boolean) {
        if (_nightMode.value == enabled) return
        _nightMode.value = enabled
        updateAudioFilters()
    }

    @Synchronized
    fun setStereoDownmix(enabled: Boolean) {
        if (_stereoDownmix.value == enabled) return
        _stereoDownmix.value = enabled
        updateAudioFilters()
    }

    @Synchronized
    private fun updateAudioFilters() {
        val activeFilters = ArrayList<String>(3)

        // Pipeline Stage 1: Downmixing (pan)
        if (_stereoDownmix.value) {
            activeFilters.add(FILTER_STEREO_DOWNMIX)
        }

        // Pipeline Stage 2: Dynamic Range Normalization (dynaudnorm)
        if (_nightMode.value) {
            activeFilters.add(FILTER_DYNAUDNORM)
        }

        // Pipeline Stage 3: Brickwall Anti-Clipping Limiter (alimiter)
        if (_softwareVolume.value > 100.0) {
            activeFilters.add(FILTER_ALIMITER)
        }

        val afChain = activeFilters.joinToString(",")
        bridge.setPropertyString("af", afChain)
    }
}
