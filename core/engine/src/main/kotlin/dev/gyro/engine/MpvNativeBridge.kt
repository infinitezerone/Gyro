package dev.gyro.engine

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstraction bridge over libmpv native operations, decoupling [GyroPlayerEngine]
 * from static JNI invocation ([GyroNative]) for complete JVM unit testability.
 */
interface MpvNativeBridge {

    val events: SharedFlow<MpvEvent>

    // ---------- Lifecycle Operations ----------

    /**
     * Creates and initializes the underlying mpv instance context.
     */
    fun create(appctx: Context)

    /**
     * Initializes the mpv core after setting pre-init options.
     */
    fun init()

    /**
     * Terminates and frees the mpv instance.
     */
    fun destroy()

    // ---------- Surface Rendering Operations ----------

    /**
     * Attaches an Android [Surface] for GPU rendering via ANativeWindow.
     */
    fun attachSurface(surface: Surface)

    /**
     * Detaches the active [Surface], switching to headless rendering.
     */
    fun detachSurface()

    /**
     * Forces immediate redraw of the current frame on the active surface.
     */
    fun redraw()

    // ---------- Commands and Options ----------

    /**
     * Dispatches an mpv command array (e.g. `["loadfile", url, "replace"]`, `["seek", sec, "absolute"]`).
     */
    fun command(cmd: Array<out String>)

    /**
     * Sets an mpv configuration option string prior to [init].
     * @return 0 on success, negative error code on failure.
     */
    fun setOptionString(name: String, value: String): Int

    // ---------- Property Queries ----------

    /**
     * Queries an mpv property as [String], or null if unset or error.
     */
    fun getPropertyString(name: String): String?

    /**
     * Queries an mpv property as [Long] (INT64), or null if unset or error.
     */
    fun getPropertyLong(name: String): Long?

    /**
     * Queries an mpv property as [Double], or null if unset or error.
     */
    fun getPropertyDouble(name: String): Double?

    /**
     * Queries an mpv property as [Boolean] (FLAG), or null if unset or error.
     */
    fun getPropertyBoolean(name: String): Boolean?

    // Convenience aliases matching historical GyroNative query names:
    fun getLong(name: String): Long? = getPropertyLong(name)
    fun getDouble(name: String): Double? = getPropertyDouble(name)
    fun getBoolean(name: String): Boolean? = getPropertyBoolean(name)

    // ---------- Property Mutations ----------

    /**
     * Sets an mpv property as [String].
     */
    fun setPropertyString(name: String, value: String)

    /**
     * Sets an mpv property as [Long] (INT64).
     */
    fun setPropertyLong(name: String, value: Long)

    /**
     * Sets an mpv property as [Double].
     */
    fun setPropertyDouble(name: String, value: Double)

    /**
     * Sets an mpv property as [Boolean] (FLAG).
     */
    fun setPropertyBoolean(name: String, value: Boolean)

    // ---------- Observation ----------

    /**
     * Registers observation for an mpv property with the specified format code.
     */
    fun observeProperty(name: String, format: Int)

    // ---------- Driver Probe Diagnostics ----------

    /**
     * Returns true if zero-copy hardware decoding (direct ANativeWindow Surface) is safe on current GPU.
     */
    fun isZeroCopySafe(): Boolean

    /**
     * Returns GPU renderer description string from GL driver probe.
     */
    fun getGpuRenderer(): String

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
}
