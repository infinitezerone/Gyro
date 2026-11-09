package dev.gyro.engine

import android.content.Context
import android.view.Surface
import dev.gyro.engine.trace.EngineLog

/**
 * Native JNI bridge to `libgyro_engine.so`.
 *
 * All rendering loops and frame synchronization are sunk completely into Rust:
 * - [attachSurface]: passes ANativeWindow to native render thread (EGLContext is persistent)
 * - [detachSurface]: signals native render thread to swap to EGL_NO_SURFACE without destroying EGLContext
 * - Native callbacks publish typed [events] to independent domain subscribers.
 */
internal object GyroNative {
    private const val TAG = "GyroNative"

    init {
        try {
            System.loadLibrary("gyro_engine")
        } catch (e: UnsatisfiedLinkError) {
            EngineLog.w(TAG, "Native library gyro_engine not loaded: ${e.message}")
        }
    }

    private val eventPipeline = MpvEventPipeline()
    val events = eventPipeline.events

    val currentEpoch: Long get() = eventPipeline.currentEpoch

    fun nextEpoch(): Long {
        val next = try {
            nativeNextEpoch()
        } catch (e: UnsatisfiedLinkError) {
            eventPipeline.nextEpoch()
        }
        eventPipeline.updateEpoch(next)
        return next
    }

    private external fun nativeNextEpoch(): Long
    private external fun nativeCurrentEpoch(): Long

    // ---------- JNI Native Methods ----------

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()

    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun redraw()
    external fun rebuildSurface()

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
    @JvmOverloads
    fun onEvent(eventId: Int, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.Lifecycle(eventId, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onEndFile(reason: Int, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.EndFile(reason, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onPropertyNone(name: String, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.PropertyChanged(name, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onPropertyLong(name: String, value: Long, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.LongProperty(name, value, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onPropertyDouble(name: String, value: Double, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.DoubleProperty(name, value, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onPropertyFlag(name: String, value: Boolean, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.FlagProperty(name, value, epoch))
    }

    @JvmStatic
    @JvmOverloads
    fun onPropertyString(name: String, value: String, epoch: Long = 0L) {
        eventPipeline.publish(MpvEvent.StringProperty(name, value, epoch))
    }
}
