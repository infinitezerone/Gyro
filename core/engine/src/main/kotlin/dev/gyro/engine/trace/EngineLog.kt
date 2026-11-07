package dev.gyro.engine.trace

import android.util.Log

/**
 * Platform-safe logger for `:core:engine`.
 *
 * Isolates `android.util.Log` to a single boundary and swallows stub exceptions when
 * the engine runs in pure JVM environments without an Android runtime.
 */
internal object EngineLog {
    fun d(tag: String, msg: String) {
        runCatching { Log.d(tag, msg) }
    }

    fun i(tag: String, msg: String) {
        runCatching { Log.i(tag, msg) }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        runCatching {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
    }
}
