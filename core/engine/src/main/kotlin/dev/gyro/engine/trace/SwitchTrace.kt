package dev.gyro.engine.trace

import android.os.SystemClock
import android.util.Log

object SwitchTrace {
    private const val TAG = "SWITCH_TRACE"

    @Volatile
    var lastSwitchStartTime: Long = 0L

    fun start(label: String, details: String = "") {
        lastSwitchStartTime = SystemClock.elapsedRealtime()
        Log.i(TAG, "[T+0ms] ▶ $label $details")
    }

    fun log(stage: String, details: String = "") {
        val now = SystemClock.elapsedRealtime()
        val diff = if (lastSwitchStartTime > 0L) now - lastSwitchStartTime else 0L
        Log.i(TAG, "[T+${diff}ms] ├─ $stage $details")
    }

    fun end(stage: String, details: String = "") {
        val now = SystemClock.elapsedRealtime()
        val diff = if (lastSwitchStartTime > 0L) now - lastSwitchStartTime else 0L
        Log.i(TAG, "[T+${diff}ms] ⏹ $stage $details (Total: ${diff}ms)")
    }
}
