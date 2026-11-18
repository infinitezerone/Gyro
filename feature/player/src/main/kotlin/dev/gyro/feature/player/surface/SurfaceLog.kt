package dev.gyro.feature.player.surface

import android.util.Log

/**
 * Platform-safe logger for the player surface host.
 *
 * Swallows stub exceptions when Compose UI tests or ViewModels exercise surface helpers on a JVM.
 */
internal object SurfaceLog {
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
