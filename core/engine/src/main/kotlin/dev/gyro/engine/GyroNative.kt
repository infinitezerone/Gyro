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

        /**
         * Emitted from the render thread once the first frame of a newly loaded file has
         * been presented. The surface keeps the previous file's last buffer until then, so
         * this is what lets the UI drop the cover over it.
         */
        const val FIRST_FRAME: Int = 1002
    }

    object EndReason {
        const val EOF: Int = 0
        const val STOP: Int = 2
        const val QUIT: Int = 3
        const val ERROR: Int = 4
        const val REDIRECT: Int = 5
    }
}
