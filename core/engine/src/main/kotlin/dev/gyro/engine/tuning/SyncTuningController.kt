package dev.gyro.engine.tuning

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
 * A/V sync calibration: the four mpv properties the tuning sheet reads and writes.
 *
 * Clamping lives here rather than in the sheet so that an out-of-range value from `mpv.conf`, a
 * restored preference and a gesture all converge on the same result. All four properties are
 * observed, so a value changed outside this controller still reaches [StateFlow] listeners.
 */
interface SyncTuningController : dev.gyro.engine.EngineSubsystem {
    val subtitleDelaySec: StateFlow<Double>
    val audioDelaySec: StateFlow<Double>
    val subtitleScale: StateFlow<Double>
    val subtitlePositionPercent: StateFlow<Long>

    override fun registerObservers() {}
    override fun onEvent(event: MpvEvent) {}
    override fun reset() { resetDelays() }
    fun observeEvents(scope: CoroutineScope): Job
    fun setSubtitleDelay(delaySec: Double)
    fun setAudioDelay(delaySec: Double)
    fun setSubtitleScale(scale: Double)
    fun setSubtitlePosition(percent: Long)
    fun resetDelays()
}

@Singleton
class RealSyncTuningController @Inject constructor(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : SyncTuningController {

    companion object {
        /** mpv clamps `sub-delay` to ±10s by default, `sub-scale` to 0.5–3, `sub-pos` to 0–100. */
        private val SUB_DELAY_RANGE = -10.0..10.0
        private val AUDIO_DELAY_RANGE = -5.0..5.0
        private val SUB_SCALE_RANGE = 0.5..3.0
        private val SUB_POS_RANGE = 0L..100L
    }

    private val _subtitleDelaySec = store.property({ it.subtitleDelaySec }) { s, v -> s.copy(subtitleDelaySec = v) }
    override val subtitleDelaySec: StateFlow<Double> = _subtitleDelaySec.asStateFlow()

    private val _audioDelaySec = store.property({ it.audioDelaySec }) { s, v -> s.copy(audioDelaySec = v) }
    override val audioDelaySec: StateFlow<Double> = _audioDelaySec.asStateFlow()

    private val _subtitleScale = store.property({ it.subtitleScale }) { s, v -> s.copy(subtitleScale = v) }
    override val subtitleScale: StateFlow<Double> = _subtitleScale.asStateFlow()

    private val _subtitlePositionPercent = store.property({ it.subtitlePositionPercent }) { s, v -> s.copy(subtitlePositionPercent = v) }
    override val subtitlePositionPercent: StateFlow<Long> = _subtitlePositionPercent.asStateFlow()

    override fun registerObservers() {
        bridge.observeProperty("sub-delay", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("audio-delay", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("sub-scale", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("sub-pos", MpvNativeBridge.Format.INT64)
    }

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.DoubleProperty -> when (event.name) {
                "sub-delay" -> _subtitleDelaySec.value = event.value
                "audio-delay" -> _audioDelaySec.value = event.value
                "sub-scale" -> _subtitleScale.value = event.value
            }
            is MpvEvent.LongProperty -> when (event.name) {
                "sub-pos" -> _subtitlePositionPercent.value = event.value
            }
            else -> Unit
        }
    }

    override fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect(::onEvent)
        }

    override fun setSubtitleDelay(delaySec: Double) {
        val clamped = (Math.round(delaySec * 10.0) / 10.0).coerceIn(SUB_DELAY_RANGE)
        _subtitleDelaySec.value = clamped
        bridge.setPropertyDouble("sub-delay", clamped)
    }

    override fun setAudioDelay(delaySec: Double) {
        val clamped = (Math.round(delaySec * 10.0) / 10.0).coerceIn(AUDIO_DELAY_RANGE)
        _audioDelaySec.value = clamped
        bridge.setPropertyDouble("audio-delay", clamped)
    }

    override fun setSubtitleScale(scale: Double) {
        val clamped = (Math.round(scale * 100.0) / 100.0).coerceIn(SUB_SCALE_RANGE)
        _subtitleScale.value = clamped
        bridge.setPropertyDouble("sub-scale", clamped)
    }

    override fun setSubtitlePosition(percent: Long) {
        val clamped = percent.coerceIn(SUB_POS_RANGE)
        _subtitlePositionPercent.value = clamped
        bridge.setPropertyLong("sub-pos", clamped)
    }

    override fun resetDelays() {
        _subtitleDelaySec.value = 0.0
        _audioDelaySec.value = 0.0
        bridge.setPropertyDouble("sub-delay", 0.0)
        bridge.setPropertyDouble("audio-delay", 0.0)
    }
}
