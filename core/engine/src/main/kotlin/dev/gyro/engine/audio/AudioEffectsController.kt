package dev.gyro.engine.audio

import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages audio filtering, 0-200% volume overdrive, anti-clipping limiter,
 * speech normalization (night mode), and stereo downmixing.
 */
class AudioEffectsController(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem, dev.gyro.engine.api.controller.AudioController {

    companion object {
        const val FILTER_STEREO_DOWNMIX =
            "lavfi=[pan=stereo|c0=0.5*c2+0.707*c0+0.707*c4|c1=0.5*c2+0.707*c1+0.707*c5]"
        const val FILTER_DYNAUDNORM =
            "lavfi=[dynaudnorm=f=200:g=15:m=10:r=0.9]"
        const val FILTER_ALIMITER =
            "lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]"
    }

    private val _softwareVolume = store.property({ it.softwareVolume }) { s, v -> s.copy(softwareVolume = v) }
    override val softwareVolume: StateFlow<Double> = _softwareVolume.asStateFlow()

    private val _nightMode = store.property({ it.nightMode }) { s, v -> s.copy(nightMode = v) }
    override val nightMode: StateFlow<Boolean> = _nightMode.asStateFlow()

    private val _stereoDownmix = store.property({ it.stereoDownmix }) { s, v -> s.copy(stereoDownmix = v) }
    override val stereoDownmix: StateFlow<Boolean> = _stereoDownmix.asStateFlow()

    private val _equalizerGains = store.property({ it.equalizerGains }) { s, v -> s.copy(equalizerGains = v) }
    override val equalizerGains: StateFlow<List<Double>> = _equalizerGains.asStateFlow()

    @Volatile
    var userBaseAf: String = ""

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
    override fun setSoftwareVolume(gain: Double) {
        val clamped = gain.coerceIn(100.0, 200.0)
        if (clamped == _softwareVolume.value) return
        val limiterWasActive = needsLimiter()
        _softwareVolume.value = clamped
        bridge.setPropertyDouble("volume", clamped)
        // Rebuilding the filter graph tears down and re-creates every libavfilter node. Doing that
        // on every slider tick is what made dragging audibly and visually stutter: the limiter only
        // needs to appear/disappear at the 100% boundary, not on each intermediate gain.
        if (needsLimiter() != limiterWasActive) updateAudioFilters()
    }

    @Synchronized
    override fun setAudioPassthrough(enabled: Boolean) {
        store.update { it.copy(audioPassthrough = enabled) }
        val target = if (enabled) "ac3,eac3,dts-hd,truehd,dtse" else ""
        bridge.setPropertyString("audio-spdif", target)
    }

    @Synchronized
    override fun resetSoftwareGain() {
        _softwareVolume.value = 100.0
        bridge.setPropertyDouble("volume", 100.0)
        updateAudioFilters()
    }

    @Synchronized
    override fun setNightMode(enabled: Boolean) {
        if (_nightMode.value == enabled) return
        _nightMode.value = enabled
        updateAudioFilters()
    }

    @Synchronized
    override fun setStereoDownmix(enabled: Boolean) {
        if (_stereoDownmix.value == enabled) return
        _stereoDownmix.value = enabled
        updateAudioFilters()
    }

    /**
     * Applies the five band gains. Bands within 0.1 dB of flat are dropped from the chain so an
     * untouched equaliser costs nothing and does not risk a filter stage the platform rejects.
     */
    @Synchronized
    override fun setEqualizerGains(gainsDb: List<Double>) {
        val bands = dev.gyro.engine.api.controller.AudioController.EQUALIZER_BANDS
        val normalised = bands.indices.map { index ->
            gainsDb.getOrNull(index)?.coerceIn(
                -dev.gyro.engine.api.controller.AudioController.EQUALIZER_GAIN_LIMIT_DB,
                dev.gyro.engine.api.controller.AudioController.EQUALIZER_GAIN_LIMIT_DB
            ) ?: 0.0
        }
        if (_equalizerGains.value == normalised) return
        _equalizerGains.value = normalised
        updateAudioFilters()
    }

    @Synchronized
    private fun updateAudioFilters() {
        val activeFilters = ArrayList<String>(4)
        if (userBaseAf.isNotBlank()) {
            activeFilters.add(userBaseAf)
        }

        // Pipeline Stage 1: Downmixing (pan)
        if (_stereoDownmix.value) {
            activeFilters.add(FILTER_STEREO_DOWNMIX)
        }

        // Pipeline Stage 2: Equalisation (equalizer, one node per non-flat band). Placed before the
        // compressor so night mode reacts to what the user shaped, not the other way round.
        equalizerFilter()?.let(activeFilters::add)

        // Pipeline Stage 3: Dynamic Range Normalization (dynaudnorm)
        if (_nightMode.value) {
            activeFilters.add(FILTER_DYNAUDNORM)
        }

        // Pipeline Stage 4: Brickwall Anti-Clipping Limiter (alimiter)
        if (needsLimiter()) {
            activeFilters.add(FILTER_ALIMITER)
        }

        val afChain = activeFilters.joinToString(",")
        bridge.setPropertyString("af", afChain)
    }

    private fun hasEqualizerBoost(): Boolean = _equalizerGains.value.any { it > 0.1 }

    private fun needsLimiter(): Boolean = _softwareVolume.value > 100.0 || hasEqualizerBoost()

    /**
     * One `equalizer` node per non-flat band, joined into a single lavfi graph.
     *
     * This only works because the bundled libavfilter was replaced with a complete build: the
     * original AAR shipped a 138 KB libavfilter in which `equalizer`, `bass` and `superequalizer`
     * were all absent, and any of them took the whole audio graph down with "No such filter".
     * Bands share a 1.5-octave width so neighbours overlap instead of leaving notches.
     */
    private fun equalizerFilter(): String? {
        val bands = dev.gyro.engine.api.controller.AudioController.EQUALIZER_BANDS
        val nodes = bands.zip(_equalizerGains.value)
            .filter { (_, gain) -> kotlin.math.abs(gain) > 0.1 }
            .map { (freq, gain) ->
                "equalizer=f=${freq.toInt()}:width_type=o:width=1.5:g=${String.format(Locale.US, "%.1f", gain)}"
            }
        if (nodes.isEmpty()) return null
        return "lavfi=[${nodes.joinToString(",")}]"
    }
}
