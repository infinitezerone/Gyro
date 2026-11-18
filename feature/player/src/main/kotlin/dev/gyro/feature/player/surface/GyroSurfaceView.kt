package dev.gyro.feature.player.surface

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
import dev.gyro.engine.api.controller.RenderController
import dev.gyro.engine.trace.SwitchTrace
import kotlin.math.abs

/** A stored buffer whose aspect has drifted from a newly reported one by more than this is re-locked. */
private const val ASPECT_EPSILON = 0.01

/**
 * High-performance, zero-overhead SurfaceView for Gyro playback engines.
 *
 * All rendering loops, EGL context lifecycles, and frame swaps execute inside the engine backend;
 * this SurfaceView acts strictly as the Android window handle provider communicating solely via
 * [RenderController].
 */
class GyroSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GyroSurfaceView"
    }

    var renderController: RenderController? = null

    /** Buffer size currently fixed on the holder; 0 until the first lock. */
    private var lockedWidth = 0
    private var lockedHeight = 0

    /**
     * Buffer dims actually applied by the platform, as of the last [surfaceChanged]. Unlike
     * [lockedWidth]/[lockedHeight] (the *requested* lock), these reflect the live BufferQueue:
     * `setFixedSize` is an async transaction, so between a re-lock and the matching
     * [surfaceChanged] the backend still draws into the previous file's buffer. The shutter-reveal
     * policy consumes this to avoid revealing the new video through stale buffer geometry.
     */
    var appliedBufferWidth by mutableIntStateOf(0)
        private set
    var appliedBufferHeight by mutableIntStateOf(0)
        private set

    /**
     * Pixel format the platform last reported through [surfaceChanged], 0 before the first call.
     */
    private var appliedFormat = 0

    private val frameRateController = ContentFrameRateController { attachedSurface ?: holder.surface }

    /** Surface whose window the engine currently holds, for the ownership check in `surfaceDestroyed`. */
    private var attachedSurface: Surface? = null

    /**
     * Set to true while entering or settling after exiting PiP mode.
     * Prevents buffer reallocation and redraw churn during transition animation.
     */
    @Volatile
    var pipTransitionActive: Boolean = false
        set(value) {
            field = value
            SurfaceLog.d(TAG, "pipTransitionActive = $value")
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val myDisplay = display ?: return
            if (displayId == myDisplay.displayId) {
                val refreshRate = myDisplay.refreshRate
                SurfaceLog.i(TAG, "onDisplayChanged: displayId=$displayId, refreshRate=$refreshRate")
                renderController?.updateDisplayRefreshRate(refreshRate)
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
     *
     * Recreating the render surface for a *colours space* change is deliberately not done here:
     * the native render thread compares its surface's colours space against the requested one on
     * every frame and rebuilds itself when they differ, which also covers PQ <-> HLG swaps that
     * keep the buffer format at 10-bit.
     */
    fun setHdrOutput(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val format = if (enabled) PixelFormat.RGBA_1010102 else PixelFormat.RGBA_8888
        holder?.setFormat(format)
        SurfaceLog.i(TAG, "setHdrOutput: enabled=$enabled, format=$format")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        SurfaceLog.i(
            TAG,
            "surfaceCreated -> attachSurface (renderController=$renderController, buffer=${lockedWidth}x$lockedHeight)"
        )
        attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        appliedBufferWidth = width
        appliedBufferHeight = height
        val formatChanged = appliedFormat != 0 && appliedFormat != format
        appliedFormat = format
        SwitchTrace.log("SURFACE_CHANGED", "view=${width}x$height buffer=${lockedWidth}x$lockedHeight format=$format")
        SurfaceLog.d(TAG, "surfaceChanged: window=${width}x$height buffer=${lockedWidth}x$lockedHeight format=$format")
        if (lockedWidth <= 0 || lockedHeight <= 0) {
            lockBufferSize(holder, width, height)
        }

        val target = renderController ?: return
        if (!target.isSurfaceAttached && holder.surface?.isValid == true) {
            SurfaceLog.i(TAG, "surfaceChanged -> re-attach unbound surface")
            attach(holder.surface)
            return
        }
        if (formatChanged) {
            SurfaceLog.i(TAG, "surfaceChanged -> buffer format changed to $format, rebuilding render surface")
            target.requestSurfaceRebuild()
            return
        }
        if (!pipTransitionActive && target.isFirstFrameRendered) {
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
        SurfaceLog.i(TAG, "lockBufferSize: ${w}x$h")
    }

    private fun attach(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        attachedSurface = surface
        val rate = display?.refreshRate
        if (rate != null) {
            renderController?.updateDisplayRefreshRate(rate)
        }
        frameRateController.reapplyCurrent()
        renderController?.attachSurface(surface)
    }

    /**
     * Releases surface ownership if this view's surface is currently attached to [targetRender],
     * without unconditionally detaching a newer surface owned by another [GyroSurfaceView].
     */
    fun releaseSurfaceOwnership(targetRender: RenderController? = renderController) {
        val surfaceToDetach = attachedSurface ?: holder.surface
        attachedSurface = null
        if (surfaceToDetach != null) {
            targetRender?.detachSurface(surfaceToDetach)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.registerDisplayListener(displayListener, handler)
        val rate = display?.refreshRate ?: dm?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        if (rate != null) {
            renderController?.updateDisplayRefreshRate(rate)
        }
        val surface = holder.surface
        if (surface != null && surface.isValid && attachedSurface !== surface) {
            SurfaceLog.i(TAG, "onAttachedToWindow -> re-attach existing surface")
            attach(surface)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        dm?.unregisterDisplayListener(displayListener)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val current = attachedSurface
        if (current != null && current !== holder.surface) {
            SurfaceLog.i(TAG, "surfaceDestroyed ignored: surface is no longer the attached one")
            return
        }
        val surfaceToDetach = current ?: holder.surface
        attachedSurface = null
        lockedWidth = 0
        lockedHeight = 0
        SurfaceLog.i(TAG, "surfaceDestroyed -> detachSurface")
        if (surfaceToDetach != null) {
            renderController?.detachSurface(surfaceToDetach)
        }
    }
}
