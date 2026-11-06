package dev.gyro.engine.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.trace.SwitchTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** A stored buffer whose aspect has drifted from a newly reported one by more than this is re-locked. */
private const val ASPECT_EPSILON = 0.01

/**
 * Computes the optimal fixed SurfaceHolder buffer dimensions by fitting the video's display aspect
 * ratio into the physical screen's native resolution bounds.
 *
 * Why this design:
 * 1. **PiP & window transition stability**: By locking the SurfaceHolder (`setFixedSize`) to an
 *    aspect-matched resolution decoupled from the transient view layout size, entering/exiting PiP
 *    or rotating the screen becomes a pure SurfaceFlinger hardware matrix scale with zero
 *    `BLASTBufferQueue` reallocation (eliminating the 40-70ms resize lag / flash).
 * 2. **Full-resolution shaders & subtitles**: Fitting to the display's physical bounds rather than
 *    clamping down to low-res video dimensions (`min(1f, ...)`) guarantees that 480p/720p videos
 *    still get a native screen-resolution OpenGL FBO for Anime4K upscaling and crisp ASS subtitles.
 * 3. **Zero re-lock on same-aspect playlist switches**: A 360p (16:9) video and a 4K (16:9) video
 *    map to the exact same screen-fitted buffer size, avoiding buffer churn across playlist items.
 */
internal fun computeDisplayFittedBufferSize(
    videoWidth: Int,
    videoHeight: Int,
    displayWidth: Int,
    displayHeight: Int
): Pair<Int, Int> {
    if (videoWidth <= 0 || videoHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
        return Pair(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1))
    }
    val shortEdge = minOf(displayWidth, displayHeight)
    val longEdge = maxOf(displayWidth, displayHeight)
    val aspect = videoWidth.toDouble() / videoHeight.toDouble()

    val maxW = if (aspect >= 1.0) longEdge else shortEdge
    val maxH = if (aspect >= 1.0) shortEdge else longEdge
    val boxAspect = maxW.toDouble() / maxH.toDouble()

    return if (aspect > boxAspect) {
        val w = maxW
        val h = (maxW / aspect).roundToInt().coerceAtLeast(1)
        Pair(w, h)
    } else {
        val h = maxH
        val w = (maxH * aspect).roundToInt().coerceAtLeast(1)
        Pair(w, h)
    }
}

private fun resolvePhysicalDisplaySize(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return Pair(bounds.width(), bounds.height())
        }
    }
    val metrics = context.applicationContext.resources.displayMetrics
    return Pair(metrics.widthPixels, metrics.heightPixels)
}

/**
 * Normalizes content frame rate to standard broadcast/digital video nominal rates
 * if it falls within a 3.5% tolerance window. Returns the nominal rate if matched,
 * or the contentFps itself if > 1f, or 0f if non-positive.
 */
internal fun normalizeContentFps(contentFps: Float): Float {
    if (contentFps <= 1f) return 0f
    val nominalRates = floatArrayOf(
        23.976f, 24.0f, 25.0f, 29.97f, 30.0f,
        48.0f, 50.0f, 59.94f, 60.0f,
        72.0f, 75.0f, 90.0f, 120.0f, 144.0f, 165.0f, 240.0f
    )
    for (nominal in nominalRates) {
        if (abs(contentFps - nominal) / nominal <= 0.035f) {
            return nominal
        }
    }
    return if (contentFps in 1.0f..1000.0f) contentFps else 0f
}

/**
 * High-performance, zero-overhead SurfaceView for Gyro MPV player.
 *
 * All rendering loops, EGL context lifecycles, and frame swaps are executed
 * strictly on the native Rust thread `gyro-render`.
 *
 * This SurfaceView only acts as the window handle provider:
 * - `surfaceCreated`: passes the native ANativeWindow to Rust.
 * - `surfaceDestroyed`: detaches surface without tearing down EGLContext.
 */
class GyroSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GyroSurfaceView"
    }

    var engine: GyroPlayerEngine? = null

    /** Buffer size currently fixed on the holder; 0 until the first lock. */
    private var lockedWidth = 0
    private var lockedHeight = 0

    /** Frame rate of the content on screen, 0 when the system should pick. */
    private var contentFrameRate = 0f

    /**
     * ColorOS (OnePlus / OPPO / Realme) OplusRefreshRateInjectorImpl walks the view hierarchy
     * when WindowInsets change (controls hide). If it finds a visible SurfaceView where
     * `!isZOrderedOnTop()`, it unconditionally overrides window refresh rate to 60Hz (refreshRateId 2),
     * causing severe frame judder/drops on 90fps/120fps content.
     *
     * In Android SurfaceView, `isZOrderedOnTop()` is a public (hidden from public SDK) virtual method.
     * By providing this method with matching signature `public boolean isZOrderedOnTop()`,
     * ART virtual dispatch directs ColorOS to this implementation.
     * Returning true causes `surfaceViewAvailable` to return false, completely neutralizing ColorOS's
     * vendor 60Hz override and letting standard Android Window/Surface display-rate voting rule.
     */
    @Suppress("unused")
    fun isZOrderedOnTop(): Boolean {
        return true
    }

    /** Surface whose window the engine currently holds, for the ownership check in `surfaceDestroyed`. */
    private var attachedSurface: Surface? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val myDisplay = display ?: return
            if (displayId == myDisplay.displayId) {
                val refreshRate = myDisplay.refreshRate
                Log.i(TAG, "onDisplayChanged: displayId=$displayId, refreshRate=$refreshRate")
                engine?.updateDisplayRefreshRate(refreshRate)
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
        if (width == lockedWidth && height == lockedHeight) return
        if (lockedWidth > 0 && lockedHeight > 0) {
            val lockedAspect = lockedWidth.toDouble() / lockedHeight
            val sameAspect = abs(width.toDouble() / height - lockedAspect) <= lockedAspect * ASPECT_EPSILON
            val sameOrSmaller = width.toLong() * height <= lockedWidth.toLong() * lockedHeight
            if (sameAspect && sameOrSmaller) return
        }
        lockBufferSize(holder, width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated -> attachSurface (engine=$engine, buffer=${lockedWidth}x$lockedHeight)")
        attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        SwitchTrace.log("SURFACE_CHANGED", "view=${width}x$height buffer=${lockedWidth}x$lockedHeight")
        Log.d(TAG, "surfaceChanged: window=${width}x$height buffer=${lockedWidth}x$lockedHeight")
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
            Log.i(TAG, "surfaceChanged -> re-attach unbound surface")
            attach(holder.surface)
            return
        }
        // Repaint so a paused player follows a relock. A playing one is already producing frames at
        // whatever size EGL reports, which is the number the renderer trusts.
        if (target.firstFrameRendered.value) {
            target.redraw()
        }
    }

    fun applyContentFrameRate(fps: Float) {
        Log.i(TAG, "applyContentFrameRate: ENTER fps=$fps (current contentFrameRate=$contentFrameRate)")
        contentFrameRate = fps
        val requested = if (fps > 1f && fps < 1000f) fps else 0f
        val normalized = normalizeContentFps(requested)

        // Vote on the Surface itself (Android 11+).
        // Uses CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS so the display compositor adapts smoothly
        // without disruptive hardware mode switches, flickering, or panel clock resyncs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val surface = attachedSurface ?: holder.surface
            if (surface != null && surface.isValid) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        surface.setFrameRate(
                            normalized,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        )
                    } else {
                        surface.setFrameRate(
                            normalized,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                        )
                    }
                    Log.i(TAG, "setFrameRate: vote=$normalized (compat=DEFAULT, SEAMLESS)")
                }.onFailure { Log.w(TAG, "setFrameRate($normalized) failed: ${it.message}") }
            }
        }
    }

    private fun lockBufferSize(holder: SurfaceHolder?, w: Int, h: Int) {
        if (holder == null || w <= 0 || h <= 0) return
        SwitchTrace.log("LOCK_BUFFER_SIZE", "${w}x$h (was ${lockedWidth}x$lockedHeight)")
        lockedWidth = w
        lockedHeight = h
        holder.setFixedSize(w, h)
        Log.i(TAG, "lockBufferSize: ${w}x$h")
    }

    private fun attach(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        attachedSurface = surface
        val rate = display?.refreshRate
        if (rate != null) {
            engine?.updateDisplayRefreshRate(rate)
        }
        // A fresh surface starts at the platform default rate; re-apply the content rate that
        // was published before it existed.
        applyContentFrameRate(contentFrameRate)
        engine?.attachSurface(surface)
    }

    /**
     * Releases surface ownership if this view's surface is currently attached to [targetEngine],
     * without unconditionally detaching a newer surface owned by another [GyroSurfaceView].
     */
    fun releaseSurfaceOwnership(targetEngine: GyroPlayerEngine? = engine) {
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
            engine?.updateDisplayRefreshRate(rate)
        }
        val surface = holder.surface
        if (surface != null && surface.isValid && attachedSurface !== surface) {
            Log.i(TAG, "onAttachedToWindow -> re-attach existing surface")
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
            Log.i(TAG, "surfaceDestroyed ignored: surface is no longer the attached one")
            return
        }
        val surfaceToDetach = current ?: holder.surface
        attachedSurface = null
        lockedWidth = 0
        lockedHeight = 0
        Log.i(TAG, "surfaceDestroyed -> detachSurface")
        if (surfaceToDetach != null) {
            engine?.detachSurface(surfaceToDetach)
        }
    }
}

/**
 * Jetpack Compose wrapper for [GyroSurfaceView].
 * Native MPV handles hardware zoom and pan directly in GPU shaders on the EGL render thread,
 * avoiding SurfaceView scaling and SurfaceFlinger BLAST transaction locks on the UI thread.
 */
@Composable
fun GyroPlayerSurface(
    modifier: Modifier = Modifier,
    engine: GyroPlayerEngine? = null
) {
    val context = LocalContext.current
    val surfaceView = remember {
        GyroSurfaceView(context).apply {
            this.engine = engine
        }
    }

    DisposableEffect(engine) {
        surfaceView.engine = engine
        onDispose {
            surfaceView.releaseSurfaceOwnership(engine)
        }
    }

    val frameRate by (engine?.videoFrameRate ?: remember { MutableStateFlow(0f) }).collectAsState()
    LaunchedEffect(frameRate) {
        Log.i("GyroPlayerSurface", "LaunchedEffect(frameRate=$frameRate), engine=$engine")
        surfaceView.applyContentFrameRate(frameRate)
    }

    // Pin the buffer to the physical display resolution fitted to the video's aspect ratio.
    // From then on window resizes (including PiP entry/exit) never touch the BufferQueue again;
    // low-res videos still get full screen-resolution FBOs for Anime4K shaders and ASS subtitles,
    // while same-aspect playlist switches reuse the exact same BufferQueue without re-locking.
    val videoDimensions by (engine?.videoDimensions ?: remember { MutableStateFlow(VideoDimensions()) }).collectAsState()
    LaunchedEffect(videoDimensions.width, videoDimensions.height) {
        if (videoDimensions.width <= 0 || videoDimensions.height <= 0) return@LaunchedEffect
        val (displayW, displayH) = resolvePhysicalDisplaySize(context)
        val (targetW, targetH) = computeDisplayFittedBufferSize(
            videoWidth = videoDimensions.width,
            videoHeight = videoDimensions.height,
            displayWidth = displayW,
            displayHeight = displayH
        )
        surfaceView.lockBufferSizeToVideo(targetW, targetH)
    }

    AndroidView(
        factory = { surfaceView },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}
