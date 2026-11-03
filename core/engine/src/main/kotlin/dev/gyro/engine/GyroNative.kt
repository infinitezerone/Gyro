package dev.gyro.engine

import android.content.Context
import android.util.Log
import android.view.Surface

/**
 * Native JNI bridge to `libgyro_engine.so`.
 *
 * All rendering loops and frame synchronization are sunk completely into Rust:
 * - [attachSurface]: passes ANativeWindow to native render thread (EGLContext is persistent)
 * - [detachSurface]: signals native render thread to swap to EGL_NO_SURFACE without destroying EGLContext
 * - Native callbacks dispatch directly to [eventListener]
 */
internal object GyroNative {
    private const val TAG = "GyroNative"

    init {
        try {
            System.loadLibrary("gyro_engine")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library gyro_engine not loaded: ${e.message}")
        }
    }

    typealias EventListener = MpvNativeBridge.EventListener

    @Volatile
    var eventListener: MpvNativeBridge.EventListener? = null

    // ---------- JNI Native Methods ----------

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()

    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun redraw()

    external fun command(cmd: Array<out String>)
    external fun setOptionString(name: String, value: String): Int

    private external fun getPropertyLong(name: String): Long
    private external fun getPropertyDouble(name: String): Double
    private external fun getPropertyBoolean(name: String): Byte
    external fun getPropertyString(name: String): String?

    fun getLong(name: String): Long? = getPropertyLong(name).takeUnless { it == Long.MIN_VALUE }
    fun getDouble(name: String): Double? = getPropertyDouble(name).takeUnless { it.isNaN() }
    fun getBoolean(name: String): Boolean? = getPropertyBoolean(name).takeIf { it.toInt() != 2 }?.toInt() != 0

    external fun setPropertyLong(name: String, value: Long)
    external fun setPropertyDouble(name: String, value: Double)
    external fun setPropertyBoolean(name: String, value: Boolean)
    external fun setPropertyString(name: String, value: String)

    external fun observeProperty(name: String, format: Int)

    // ---------- Driver Probe Diagnostics ----------
    external fun isZeroCopySafe(): Boolean
    external fun getGpuRenderer(): String

    // ---------- Native Callback Dispatchers (Called from Rust) ----------

    @JvmStatic
    fun onEvent(eventId: Int) {
        eventListener?.onEvent(eventId)
    }

    @JvmStatic
    fun onPropertyNone(name: String) {
        eventListener?.onProperty(name)
    }

    @JvmStatic
    fun onPropertyLong(name: String, value: Long) {
        eventListener?.onPropertyLong(name, value)
    }

    @JvmStatic
    fun onPropertyDouble(name: String, value: Double) {
        eventListener?.onPropertyDouble(name, value)
    }

    @JvmStatic
    fun onPropertyFlag(name: String, value: Boolean) {
        eventListener?.onPropertyFlag(name, value)
    }

    @JvmStatic
    fun onPropertyString(name: String, value: String) {
        eventListener?.onPropertyString(name, value)
    }

    // ---------- Format & Event Constants ----------

    object Format {
        const val NONE: Int = 0
        const val STRING: Int = 1
        const val FLAG: Int = 3
        const val INT64: Int = 4
        const val DOUBLE: Int = 5
    }

    object Event {
        const val SHUTDOWN: Int = 1
        const val END_FILE: Int = 7
        const val FILE_LOADED: Int = 8
        const val TRACKS_CHANGED: Int = 10
        const val PLAYBACK_RESTART: Int = 21
        const val PROPERTY_CHANGE: Int = 22
    }
}
