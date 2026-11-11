package dev.gyro.engine.api.controller

import kotlinx.coroutines.flow.StateFlow

/**
 * Controller interface for audio adjustments, volume overdrive, normalization, and downmixing.
 */
interface AudioController {
    val softwareVolume: StateFlow<Double>
    val nightMode: StateFlow<Boolean>
    val stereoDownmix: StateFlow<Boolean>

    fun setSoftwareVolume(gain: Double)
    fun resetSoftwareGain()
    fun setNightMode(enabled: Boolean)
    fun setStereoDownmix(enabled: Boolean)
    fun setAudioPassthrough(enabled: Boolean)
}
