package dev.gyro.engine.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.trace.EngineLog
import dev.gyro.engine.trace.SwitchTrace
import kotlin.math.abs

/** A stored buffer whose aspect has drifted from a newly reported one by more than this is re-locked. */
private const val ASPECT_EPSILON = 0.01

/**
 * High-performance, zero-overhead SurfaceView for Gyro MPV player.
 *
 * All rendering loops, EGL context lifecycles, and frame swaps are executed
 * strictly on the native Rust thread `gyro-render`.
 *
 * This SurfaceView only acts as the window handle provider:
 * - `surfaceCreated`: passes the native ANativeWindow to Rust.
 * - `surfaceDestroyed`: detaches surface without tearing down EGLContext.
 *
 * Pure display math lives in [DisplayFit.kt], display capability probes in
 * [DisplayCapabilities.kt], frame capture in [SurfaceCapture.kt], and the refresh-rate vote
 * in [ContentFrameRateController] - this class is the window-handle and buffer-format bridge.
 */
class GyroSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GyroSurfaceView"
    }

    var engine: PlayerEngine? = null

    /** Buffer size currently fixed on the holder; 0 until the first lock. */
    private var lockedWidth = 0
    private var lockedHeight = 0

    /**
     * Buffer dims actually applied by the platform, as of the last [surfaceChanged]. Unlike
     * [lockedWidth]/[lockedHeight] (the *requested* lock), these reflect the live BufferQueue:
     * `setFixedSize` is an async transaction, so between a re-lock and the matching
     * [surfaceChanged] mpv still draws into the previous file's buffer. The shutter-reveal
     * policy consumes this to avoid revealing the new video through stale buffer geometry -
     * the "corner jump" regression of 2026-11-08.
     */
    var appliedBufferWidth by mutableIntStateOf(0)
        private set
    var appliedBufferHeight by mutableIntStateOf(0)
        private set

    /**
     * Pixel format the platform last reported through [surfaceChanged], 0 before the first call.
     *
     * The surface colourspace is baked into the EGL surface, so a format change (8-bit <-> 10-bit
     * for HDR) has to rebuild it; the callback is the only reliable signal that the new format is
     * actually in effect.
     */
    private var appliedFormat = 0

    private val frameRateController = ContentFrameRateController { attachedSurface ?: holder.surface }

    /** Surface whose window the engine currently holds, for the ownership check in `surfaceDestroyed`. */
    private var attachedSurface: Surface? = null

    /**
     * Set to true while entering or settling after exiting PiP mode.
     * Prevents buffer reallocation and mpv redraw churn during transition animation.
     */
    @Volatile
    var pipTransitionActive: Boolean = false
        set(value) {
            field = value
            EngineLog.d(TAG, "pipTransitionActive = $value")
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val myDisplay = display ?: return
            if (displayId == myDisplay.displayId) {
                val refreshRate = myDisplay.refreshRate
                EngineLog.i(TAG, "onDisplayChanged: displayId=$displayId, refreshRate=$refreshRate")
                engine?.geometry?.updateDisplayRefreshRate(refreshRate)
            }
        }
    }

    init {
        holder?.addCallback(this)
    }

    /**
     * Locks the buffer to the video's display size. Called whenever the engine reports new video
     * dimensions - a new file, or a file whose display rotation flips the aspect.
     *
     * Same-aspect sizes skip the re-lock: an equally shaped buffer renders any resolution without
     * distortion, and skipping keeps file-to-file switches on one BufferQueue.
     */
    fun lockBufferSizeToVideo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (pipTransitionActive) return
        if (width == lockedWidth && height == lockedHeight) return
        if (lockedWidth > 0 && lockedHeight > 0) {
            val lockedAspect = lockedWidth.toDouble() / lockedHeight
            val sameAspect = abs(width.toDouble() / height - lockedAspect) <= lockedAspect * ASPECT_EPSILON
            val sameOrSmaller = width.toLong() * height <= lockedWidth.toLong() * lockedHeight
            if (sameAspect && sameOrSmaller) return
        }
        lockBufferSize(holder, width, height)
    }

    /**
     * Sets the buffer format to 10-bit RGBA_1010102 when HDR output is enabled,
     * or standard RGBA_8888 for SDR content.
     */
    fun setHdrOutput(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val format = if (enabled) PixelFormat.RGBA_1010102 else PixelFormat.RGBA_8888
            holder?.setFormat(format)
            EngineLog.i(TAG, "setHdrOutput: enabled=$enabled, format=$format")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        EngineLog.i(TAG, "surfaceCreated -> attachSurface (engine=$engine, buffer=${lockedWidth}x$lockedHeight)")
        attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        appliedBufferWidth = width
        appliedBufferHeight = height
        val formatChanged = appliedFormat != 0 && appliedFormat != format
        appliedFormat = format
        SwitchTrace.log("SURFACE_CHANGED", "view=${width}x$height buffer=${lockedWidth}x$lockedHeight format=$format")
        EngineLog.d(TAG, "surfaceChanged: window=${width}x$height buffer=${lockedWidth}x$lockedHeight format=$format")
        if (lockedWidth <= 0 || lockedHeight <= 0) {
            // No video dimensions have arrived yet: let the buffer follow the view so first paint
            // has something to draw into. [lockBufferSizeToVideo] re-locks once mpv reports size.
            lockBufferSize(holder, width, height)
        }

        val target = engine ?: return
        // A live surface with the native side unbound is the frozen-picture case: `surfaceDestroyed`
        // ran, no `surfaceCreated` followed because the view was reused, and drawing silently stops
        // while audio keeps playing. Re-bind before deciding whether a repaint is needed.
        if (!target.isSurfaceAttached && holder.surface?.isValid == true) {
            EngineLog.i(TAG, "surfaceChanged -> re-attach unbound surface")
            attach(holder.surface)
            return
        }
        // An HDR switch flips the window between 8-bit and 10-bit buffers. The EGL surface was
        // created for the previous format and carries its colourspace, so it has to be rebuilt;
        // rebuilding also repaints, which makes the redraw below redundant.
        if (formatChanged) {
            EngineLog.i(TAG, "surfaceChanged -> buffer format changed to $format, rebuilding render surface")
            target.requestSurfaceRebuild()
            return
        }
        // Repaint so a paused player follows a relock. A playing one is already producing frames at
        // whatever size EGL reports, which is the number the renderer trusts.
        if (!pipTransitionActive && target.geometry.firstFrameRendered.value) {
            target.redraw()
        }
    }

    /**
     * Captures the current visible frame straight from the hardware surface. See
     * [captureSurfaceBitmap] for the pixel-copy mechanics and failure semantics.
     */
    suspend fun captureBitmap(): Bitmap? = captureSurfaceBitmap(
        surface = holder.surface,
        viewWidth = width,
        viewHeight = height,
        bufferWidth = appliedBufferWidth,
        bufferHeight = appliedBufferHeight
    )

    fun applyContentFrameRate(fps: Float) = frameRateController.apply(fps)

    private fun lockBufferSize(holder: SurfaceHolder?, w: Int, h: Int) {
        if (holder == null || w <= 0 || h <= 0) return
        SwitchTrace.log("LOCK_BUFFER_SIZE", "${w}x$h (was ${lockedWidth}x$lockedHeight)")
        lockedWidth = w
        lockedHeight = h
        holder.setFixedSize(w, h)
        EngineLog.i(TAG, "lockBufferSize: ${w}x$h")
    }

    private fun attach(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        attachedSurface = surface
        val rate = display?.refreshRate
        if (rate != null) {
            engine?.geometry?.updateDisplayRefreshRate(rate)
        }
        // A fresh surface starts at the platform default rate; re-apply the content rate that
        // was published before it existed.
        frameRateController.reapplyCurrent()
        engine?.attachSurface(surface)
    }

    /**
     * Releases surface ownership if this view's surface is currently attached to [targetEngine],
     * without unconditionally detaching a newer surface owned by another [GyroSurfaceView].
     */
    fun releaseSurfaceOwnership(targetEngine: PlayerEngine? = engine) {
        val surfaceToDetach = attachedSurface ?: holder.surface
        attachedSurface = null
        if (surfaceToDetach != null) {
            targetEngine?.detachSurface(surfaceToDetach)
        }
    }

    /**
     * Re-binds a surface that is still valid but was unbound earlier.
     *
     * Compose reuses this view across layouts - rotation, full screen, leaving PiP - so it can be
     * detached and attached again without `surfaceCreated` firing a second time. `surfaceDestroyed`
     * has already unbound the native side by then, and nothing paints afterwards: audio keeps
     * playing while the picture stays frozen on the last frame.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, handler)
        val rate = display?.refreshRate ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        if (rate != null) {
            engine?.geometry?.updateDisplayRefreshRate(rate)
        }
        val surface = holder.surface
        if (surface != null && surface.isValid && attachedSurface !== surface) {
            EngineLog.i(TAG, "onAttachedToWindow -> re-attach existing surface")
            attach(surface)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(displayListener)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Only the surface the engine actually holds may unbind it. A new view can attach before the
        // old one detaches during a Compose layout change, and an unconditional detach then tears
        // down the fresh surface: the picture freezes while audio keeps playing.
        val current = attachedSurface
        if (current != null && current !== holder.surface) {
            EngineLog.i(TAG, "surfaceDestroyed ignored: surface is no longer the attached one")
            return
        }
        val surfaceToDetach = current ?: holder.surface
        attachedSurface = null
        lockedWidth = 0
        lockedHeight = 0
        EngineLog.i(TAG, "surfaceDestroyed -> detachSurface")
        if (surfaceToDetach != null) {
            engine?.detachSurface(surfaceToDetach)
        }
    }
}
