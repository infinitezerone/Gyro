package dev.gyro.engine.trace

object SwitchTrace {
    private const val TAG = "SWITCH_TRACE"

    @Volatile
    var lastSwitchStartTime: Long = 0L

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    fun start(label: String, details: String = "") {
        lastSwitchStartTime = monotonicMs()
        EngineLog.i(TAG, "[T+0ms] ▶ $label $details")
    }

    fun log(stage: String, details: String = "") {
        val now = monotonicMs()
        val diff = if (lastSwitchStartTime > 0L) now - lastSwitchStartTime else 0L
        EngineLog.i(TAG, "[T+${diff}ms] ├─ $stage $details")
    }

    fun end(stage: String, details: String = "") {
        val now = monotonicMs()
        val diff = if (lastSwitchStartTime > 0L) now - lastSwitchStartTime else 0L
        EngineLog.i(TAG, "[T+${diff}ms] ⏹ $stage $details (Total: ${diff}ms)")
    }
}
