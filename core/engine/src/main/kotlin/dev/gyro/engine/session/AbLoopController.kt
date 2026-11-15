package dev.gyro.engine.session

import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A-B 循环控制 (AbLoopController)。
 *
 * 直接驱动 mpv 的 `ab-loop-a/b` 属性与 `ab-loop` 命令：
 * - [cycleLoopPoint] 提供与 mpv 原生键一致的三态循环：设 A → 设 B → 清除；
 * - 两点通过 STRING 格式观察（值为 "no" 或秒数字符串），外部改动（如控制台）同样回流快照；
 * - 快照存储单位为秒（-1.0 表示未设置），UI 层换算成进度条分数。
 */
class AbLoopController(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem {

    companion object {
        private const val TAG = "AbLoopController"
        private const val NO_LOOP = -1.0

        /** 解析 mpv 的 ab-loop 属性字符串（"no" 或秒数），未设置返回 [NO_LOOP]。 */
        private fun parseLoopPoint(raw: String): Double {
            if (raw.isBlank() || raw.equals("no", ignoreCase = true)) return NO_LOOP
            return raw.toDoubleOrNull()?.coerceAtLeast(0.0) ?: NO_LOOP
        }
    }

    private val _abLoopASeconds = store.property({ it.abLoopASeconds }) { s, v -> s.copy(abLoopASeconds = v) }
    val abLoopASeconds: StateFlow<Double> = _abLoopASeconds.asStateFlow()

    private val _abLoopBSeconds = store.property({ it.abLoopBSeconds }) { s, v -> s.copy(abLoopBSeconds = v) }
    val abLoopBSeconds: StateFlow<Double> = _abLoopBSeconds.asStateFlow()

    override fun registerObservers() {
        bridge.observeProperty("ab-loop-a", MpvNativeBridge.Format.STRING)
        bridge.observeProperty("ab-loop-b", MpvNativeBridge.Format.STRING)
    }

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.StringProperty -> when (event.name) {
                "ab-loop-a" -> _abLoopASeconds.value = parseLoopPoint(event.value)
                "ab-loop-b" -> _abLoopBSeconds.value = parseLoopPoint(event.value)
            }
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.FILE_LOADED,
                MpvNativeBridge.Event.END_FILE,
                MpvNativeBridge.Event.SHUTDOWN -> {
                    _abLoopASeconds.value = NO_LOOP
                    _abLoopBSeconds.value = NO_LOOP
                }
            }
            else -> Unit
        }
    }

    override fun reset() {
        _abLoopASeconds.value = NO_LOOP
        _abLoopBSeconds.value = NO_LOOP
    }

    /**
     * 三态循环：未设 A → 设 A；已设 A 未设 B → 设 B；两端齐备 → 清除。
     */
    fun cycleLoopPoint() {
        val aSet = _abLoopASeconds.value != NO_LOOP
        val bSet = _abLoopBSeconds.value != NO_LOOP
        when {
            !aSet || !bSet -> {
                bridge.command(arrayOf("ab-loop"))
                EngineLog.i(TAG, "cycleLoopPoint: advancing ab-loop at playhead (aSet=$aSet, bSet=$bSet)")
            }
            else -> clearLoop()
        }
    }

    fun setLoopA(seconds: Double) {
        bridge.setPropertyString("ab-loop-a", seconds.toString())
        _abLoopASeconds.value = seconds
        EngineLog.i(TAG, "setLoopA: explicit A set to ${seconds}s")
    }

    fun setLoopB(seconds: Double) {
        bridge.setPropertyString("ab-loop-b", seconds.toString())
        _abLoopBSeconds.value = seconds
        EngineLog.i(TAG, "setLoopB: explicit B set to ${seconds}s")
    }

    fun clearLoop() {
        bridge.setPropertyString("ab-loop-a", "no")
        bridge.setPropertyString("ab-loop-b", "no")
        _abLoopASeconds.value = NO_LOOP
        _abLoopBSeconds.value = NO_LOOP
        EngineLog.i(TAG, "clearLoop: A/B points cleared")
    }
}
