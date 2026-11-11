package dev.gyro.engine.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Display
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.trace.EngineLog
import dev.gyro.engine.trace.SwitchTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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

    var engine: dev.gyro.engine.api.PlayerEngine? = null

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

    /** Frame rate of the content on screen, 0 when the system should pick. */
    private var contentFrameRate = 0f


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
     * Captures the current visible frame directly from the hardware [SurfaceView]
     * using [PixelCopy], completely bypassing decoder memory access restrictions
     * (such as zero-copy MediaCodec hardware decoding).
     */
    suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        val s = holder.surface
        if (s == null || !s.isValid || width <= 0 || height <= 0) {
            EngineLog.w(TAG, "captureBitmap aborted: surface valid=${s?.isValid}, size=${width}x$height")
            return@withContext null
        }
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            EngineLog.e(TAG, "captureBitmap Bitmap.createBitmap failed", t)
            return@withContext null
        }
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            try {
                PixelCopy.request(
                    this@GyroSurfaceView,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            EngineLog.i(TAG, "captureBitmap PixelCopy SUCCESS: ${bitmap.width}x${bitmap.height}")
                            continuation.resume(bitmap)
                        } else {
                            EngineLog.w(TAG, "captureBitmap PixelCopy failed: code=$copyResult")
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    },
                    handler
                )
            } catch (e: Throwable) {
                EngineLog.e(TAG, "captureBitmap PixelCopy.request exception", e)
                bitmap.recycle()
                continuation.resume(null)
            }
        }
    }

    fun applyContentFrameRate(fps: Float) {
        EngineLog.i(TAG, "applyContentFrameRate: ENTER fps=$fps (current contentFrameRate=$contentFrameRate)")
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
                    EngineLog.i(TAG, "setFrameRate: vote=$normalized (compat=DEFAULT, SEAMLESS)")
                }.onFailure { EngineLog.w(TAG, "setFrameRate($normalized) failed: ${it.message}") }
            }
        }
    }

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
        applyContentFrameRate(contentFrameRate)
        engine?.attachSurface(surface)
    }

    /**
     * Releases surface ownership if this view's surface is currently attached to [targetEngine],
     * without unconditionally detaching a newer surface owned by another [GyroSurfaceView].
     */
    fun releaseSurfaceOwnership(targetEngine: dev.gyro.engine.api.PlayerEngine? = engine) {
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

/**
 * Jetpack Compose wrapper for [GyroSurfaceView].
 * Native MPV handles hardware zoom and pan directly in GPU shaders on the EGL render thread,
 * avoiding SurfaceView scaling and SurfaceFlinger BLAST transaction locks on the UI thread.
 */
@Composable
fun GyroPlayerSurface(
    modifier: Modifier = Modifier,
    engine: dev.gyro.engine.api.PlayerEngine? = null,
    videoDimensions: VideoDimensions? = null,
    videoFrameRate: Float? = null,
    pipTransitionActive: Boolean = false,
    onAppliedBufferSizeChanged: ((androidx.compose.ui.unit.IntSize) -> Unit)? = null,
    onSurfaceViewAvailable: ((GyroSurfaceView) -> Unit)? = null
) {
    val context = LocalContext.current
    val surfaceView = remember {
        GyroSurfaceView(context).apply {
            this.engine = engine
            onSurfaceViewAvailable?.invoke(this)
        }
    }
    LaunchedEffect(surfaceView) {
        onSurfaceViewAvailable?.invoke(surfaceView)
    }

    SideEffect {
        surfaceView.pipTransitionActive = pipTransitionActive
    }

    // Tell the engine up front whether the panel can present HDR. Without it mpv is told to
    // output PQ on an SDR display, where it skips tone-mapping and the picture washes out.
    LaunchedEffect(engine) {
        engine?.geometry?.setDisplayHdrCapable(context.displaySupportsHdr())
    }

    val isHdr by (engine?.geometry?.isHdr ?: remember { MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(isHdr) {
        val activity = context.findActivity()
        val canHdr = isHdr && context.displaySupportsHdr()
        surfaceView.setHdrOutput(canHdr)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.window?.colorMode = if (canHdr) {
                ActivityInfo.COLOR_MODE_HDR
            } else if (context.displaySupportsWideColorGamut()) {
                ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val activity = context.findActivity()
                activity?.window?.colorMode = if (context.displaySupportsWideColorGamut()) {
                    ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                } else {
                    ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
        }
    }

    DisposableEffect(engine) {
        surfaceView.engine = engine
        onDispose {
            surfaceView.releaseSurfaceOwnership(engine)
        }
    }

    LaunchedEffect(surfaceView) {
        snapshotFlow { androidx.compose.ui.unit.IntSize(surfaceView.appliedBufferWidth, surfaceView.appliedBufferHeight) }
            .collect { onAppliedBufferSizeChanged?.invoke(it) }
    }

    val observedFrameRate by (engine?.geometry?.videoFrameRate ?: remember { MutableStateFlow(0f) }).collectAsState()
    val effectiveFrameRate = videoFrameRate ?: observedFrameRate
    LaunchedEffect(effectiveFrameRate) {
        EngineLog.i("GyroPlayerSurface", "LaunchedEffect(frameRate=$effectiveFrameRate), engine=$engine")
        surfaceView.applyContentFrameRate(effectiveFrameRate)
    }

    // Pin the buffer to the physical display resolution fitted to the video's aspect ratio.
    // From then on window resizes (including PiP entry/exit) never touch the BufferQueue again;
    // low-res videos still get full screen-resolution FBOs for Anime4K shaders and ASS subtitles,
    // while same-aspect playlist switches reuse the exact same BufferQueue without re-locking.
    val observedDimensions: VideoDimensions by (engine?.geometry?.videoDimensions ?: remember { MutableStateFlow(VideoDimensions()) }).collectAsState(initial = VideoDimensions())
    val effectiveDimensions = videoDimensions ?: observedDimensions
    LaunchedEffect(effectiveDimensions.width, effectiveDimensions.height) {
        if (effectiveDimensions.width <= 0 || effectiveDimensions.height <= 0) return@LaunchedEffect
        val (displayW, displayH) = resolvePhysicalDisplaySize(context)
        val (targetW, targetH) = computeDisplayFittedBufferSize(
            videoWidth = effectiveDimensions.width,
            videoHeight = effectiveDimensions.height,
            displayWidth = displayW,
            displayHeight = displayH
        )
        surfaceView.lockBufferSizeToVideo(targetW, targetH)
    }

    AndroidView(
        factory = {
            if (effectiveDimensions.width > 0 && effectiveDimensions.height > 0) {
                val (displayW, displayH) = resolvePhysicalDisplaySize(context)
                val (targetW, targetH) = computeDisplayFittedBufferSize(
                    videoWidth = effectiveDimensions.width,
                    videoHeight = effectiveDimensions.height,
                    displayWidth = displayW,
                    displayHeight = displayH
                )
                surfaceView.lockBufferSizeToVideo(targetW, targetH)
            }
            surfaceView
        },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** True when the current display advertises HDR support to the platform. */
@Suppress("DEPRECATION")
private fun Context.displaySupportsHdr(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val display = findActivity()?.windowManager?.defaultDisplay ?: return false
    return display.isHdr
}

/** True when the current display advertises wide color gamut (P3) support. */
@Suppress("DEPRECATION")
private fun Context.displaySupportsWideColorGamut(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val display = findActivity()?.windowManager?.defaultDisplay ?: return false
    return display.isWideColorGamut
}
