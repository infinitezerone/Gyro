package dev.gyro.engine.api.controller

import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for audio adjustments, volume overdrive, normalization, and downmixing.
 */
interface AudioController {
    val softwareVolume: StateFlow<Double>
    val nightMode: StateFlow<Boolean>
    val stereoDownmix: StateFlow<Boolean>

    /**
     * Five-band graphical equaliser, in dB, always [EQUALIZER_BANDS] entries.
     *
     * Separate from [nightMode] by design: night mode compresses dynamics over time (loud parts
     * pulled down, quiet parts lifted) while this changes the spectral balance. Neither replaces
     * the other, and they run in series.
     */
    val equalizerGains: StateFlow<List<Double>>

    fun setSoftwareVolume(gain: Double)
    fun resetSoftwareGain()
    fun setNightMode(enabled: Boolean)
    fun setStereoDownmix(enabled: Boolean)
    fun setAudioPassthrough(enabled: Boolean)
    fun setEqualizerGains(gainsDb: List<Double>)

    companion object {
        /** Centre frequencies in Hz for [equalizerGains]. */
        val EQUALIZER_BANDS = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
        const val EQUALIZER_GAIN_LIMIT_DB = 12.0
    }
}
