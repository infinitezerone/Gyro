package dev.gyro.engine.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
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

internal data class DisplayModeCandidate(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

/**
 * Normalizes content frame rate to standard broadcast/digital video nominal rates
 * if it falls within a 3.5% tolerance window. This prevents transient frame rate estimation
 * noise (e.g. 87.35fps during playback startup for a 90fps video) from falling out of
 * integer multiple matching and triggering false hardware display mode churn.
 */
internal fun normalizeContentFps(contentFps: Float): Float {
    if (contentFps <= 1f) return 0f
    val nominalRates = floatArrayOf(
        23.976f, 24.0f, 25.0f, 29.97f, 30.0f,
        48.0f, 50.0f, 59.94f, 60.0f,
        72.0f, 90.0f, 120.0f, 144.0f
    )
    for (nominal in nominalRates) {
        if (abs(contentFps - nominal) / nominal <= 0.035f) {
            return nominal
        }
    }
    return contentFps
}

/**
 * Selects the optimal physical display mode for [contentFps] at the active resolution
 * ([activeWidth] x [activeHeight]).
 *
 * Strategy:
 * 1. If one or more modes at the active resolution are an exact integer multiple of [contentFps]
 *    (e.g. 120Hz for 24/30/40/60/120fps, 90Hz for 45/90fps, 144Hz for 48/72/144fps), pick the
 *    highest exact-multiple mode up to 120Hz (or the lowest exact multiple >= [contentFps] if all
 *    exact multiples exceed 120Hz).
 * 2. If no mode is an integer multiple of [contentFps] (e.g. 75.07fps), pick the highest refresh
 *    rate mode up to 120Hz (or highest overall if [contentFps] > 120Hz) so VSync quantization step
 *    size (8.33ms at 120Hz vs 11.11ms at 90Hz or 16.67ms at 60Hz) is minimized.
 */
internal fun selectPreferredDisplayMode(
    modes: List<DisplayModeCandidate>,
    activeWidth: Int,
    activeHeight: Int,
    contentFps: Float
): DisplayModeCandidate? {
    if (contentFps <= 1f || modes.isEmpty()) return null
    val targetFps = normalizeContentFps(contentFps)
    Log.i("GyroSurfaceView", "selectPreferredDisplayMode: contentFps=$contentFps, targetFps=$targetFps, active=${activeWidth}x$activeHeight, modes=${modes.map { "${it.modeId}:${it.refreshRate}Hz" }}")
    val resolutionMatched = modes.filter { it.width == activeWidth && it.height == activeHeight }
        .ifEmpty { modes }

    val exactMultiples = resolutionMatched.filter { mode ->
        if (mode.refreshRate < targetFps - 0.5f) return@filter false
        val mult = (mode.refreshRate / targetFps).roundToInt()
        mult >= 1 && abs(mode.refreshRate - targetFps * mult) <= 0.8f
    }

    if (exactMultiples.isNotEmpty()) {
        val standardMultiples = exactMultiples.filter { it.refreshRate <= 120.5f }
        return if (standardMultiples.isNotEmpty()) {
            standardMultiples.maxByOrNull { it.refreshRate }
        } else {
            exactMultiples.minByOrNull { it.refreshRate }
        }
    }

    val standardHigh = resolutionMatched.filter { it.refreshRate <= 120.5f && it.refreshRate >= targetFps - 0.5f }
    return if (standardHigh.isNotEmpty()) {
        standardHigh.maxByOrNull { it.refreshRate }
    } else {
        resolutionMatched.maxByOrNull { it.refreshRate }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
 *
 * Buffer policy (the media3 shape): the buffer is locked to the VIDEO's own aspect via
 * [lockBufferSizeToVideo] and never follows the window. Once locked, every visible resize -
 * full screen, PiP, rotation - is a pure compositor transform of an untouched BufferQueue, so
 * there is no buffer rebuild to flash and no aspect mismatch to stretch the picture with.
 * Keeping the view's aspect equal to the buffer's aspect is the caller's job (see
 * [GyroPlayerSurface] and its containers).
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

    /** Active Choreographer callback sampling the compositor's real VSync interval, if any. */
    private var activeFrameCallback: Choreographer.FrameCallback? = null

    /** Listens for system display refresh-rate switches so mpv's `display-fps-override` stays in sync. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && isAttachedToWindow) {
                measureDisplayRate { measuredFps -> engine?.onDisplayRefreshRateMeasured(measuredFps) }
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
        target.redraw()
        measureDisplayRate { measuredFps -> target.onDisplayRefreshRateMeasured(measuredFps) }
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

        // Measure actual VSync cadence delivered by the compositor and notify mpv engine.
        measureDisplayRate { measuredFps -> engine?.onDisplayRefreshRateMeasured(measuredFps) }
    }

    private fun lockBufferSize(holder: SurfaceHolder?, w: Int, h: Int) {
        if (holder == null || w <= 0 || h <= 0) return
        lockedWidth = w
        lockedHeight = h
        holder.setFixedSize(w, h)
        Log.i(TAG, "lockBufferSize: ${w}x$h")
    }

    private fun attach(surface: Surface?) {
        if (surface == null || !surface.isValid) return
        attachedSurface = surface
        // A fresh surface starts at the platform default rate; re-apply the content rate that
        // was published before it existed.
        applyContentFrameRate(contentFrameRate)
        engine?.attachSurface(surface)
    }

    /**
     * Two-stage measurement of the refresh rate the compositor is actually delivering to this window:
     * - Stage 1 (after 12 intervals, ~100-130ms): reports an initial median VSync rate so mpv never
     *   runs uncalibrated at startup.
     * - Stage 2 (intervals 15..44, after ~150ms mode-switch warm-up): reports the settled median VSync
     *   rate after SurfaceFlinger finishes switching the physical panel refresh rate.
     */
    private fun measureDisplayRate(onMeasured: (Float) -> Unit) {
        val choreographer = runCatching { Choreographer.getInstance() }.getOrNull() ?: return
        activeFrameCallback?.let { choreographer.removeFrameCallback(it) }
        val totalSamples = 45
        val quickSamples = 12
        val settleStart = 15
        val intervals = LongArray(totalSamples)
        var lastNs = 0L
        var count = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastNs != 0L) {
                    intervals[count++] = frameTimeNanos - lastNs
                    if (count == quickSamples) {
                        val quickSlice = intervals.copyOfRange(0, quickSamples)
                        quickSlice.sort()
                        val medianNs = quickSlice[quickSlice.size / 2]
                        if (medianNs > 0L) {
                            onMeasured(1_000_000_000f / medianNs)
                        }
                    }
                }
                lastNs = frameTimeNanos
                if (count < totalSamples) {
                    choreographer.postFrameCallback(this)
                } else {
                    activeFrameCallback = null
                    val settledSlice = intervals.copyOfRange(settleStart, totalSamples)
                    settledSlice.sort()
                    val medianNs = settledSlice[settledSlice.size / 2]
                    if (medianNs > 0L) {
                        onMeasured(1_000_000_000f / medianNs)
                    }
                }
            }
        }
        activeFrameCallback = callback
        choreographer.postFrameCallback(callback)
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
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.registerDisplayListener(displayListener, handler)
        }
        val surface = holder.surface
        if (surface != null && surface.isValid && attachedSurface !== surface) {
            Log.i(TAG, "onAttachedToWindow -> re-attach existing surface")
            attach(surface)
        }
    }

    override fun onDetachedFromWindow() {
        runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        activeFrameCallback?.let { callback ->
            runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        }
        activeFrameCallback = null
        super.onDetachedFromWindow()
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
