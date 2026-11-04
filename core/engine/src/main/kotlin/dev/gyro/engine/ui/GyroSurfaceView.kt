package dev.gyro.engine.ui

import android.content.Context
import android.view.Choreographer
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.GyroNative
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.VideoDimensions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.min

/** A stored buffer whose aspect has drifted from a newly reported one by more than this is re-locked. */
private const val ASPECT_EPSILON = 0.01

/** Frames sampled by [GyroSurfaceView.measureDisplayRate]; ~440ms at 90Hz. */
private const val MEASURED_FRAMES = 40

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

    /** Surface whose window the engine currently holds, for the ownership check in `surfaceDestroyed`. */
    private var attachedSurface: Surface? = null

    init {
        holder.addCallback(this)
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
        if (lockedWidth > 0) {
            val lockedAspect = lockedWidth.toDouble() / lockedHeight
            if (abs(width.toDouble() / height - lockedAspect) <= lockedAspect * ASPECT_EPSILON) return
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

        val target = engine
        // A live surface with the native side unbound is the frozen-picture case: `surfaceDestroyed`
        // ran, no `surfaceCreated` followed because the view was reused, and drawing silently stops
        // while audio keeps playing. Re-bind before deciding whether a repaint is needed.
        if (target != null && !target.isSurfaceAttached && holder.surface?.isValid == true) {
            Log.i(TAG, "surfaceChanged -> re-attach unbound surface")
            attach(holder.surface)
            return
        }
        // Repaint so a paused player follows a relock. A playing one is already producing frames at
        // whatever size EGL reports, which is the number the renderer trusts.
        if (target != null) {
            target.redraw()
        } else {
            GyroNative.redraw()
        }
    }

    /**
     * Measures the rate the compositor actually runs at and hands it to the engine.
     *
     * `Display.mode.refreshRate` reports 120 on this device while SurfaceFlinger composites this
     * window at 60, so the platform value cannot be trusted: mpv would schedule every frame for a
     * 120Hz grid the panel never produces. Choreographer callbacks follow the real vsync, so twenty
     * consecutive ones give the number mpv actually needs.
     */
    fun measureDisplayRate(onMeasured: (Float) -> Unit) {
        val choreographer = Choreographer.getInstance()
        val intervals = LongArray(MEASURED_FRAMES - 1)
        var previous = 0L
        var frames = 0
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (previous != 0L) {
                    // The median, not the mean: a frame lost to a transition or a GC pause would
                    // otherwise drag the average down and hand mpv the wrong rate - the exact bug
                    // this measurement exists to fix.
                    intervals[frames] = frameTimeNanos - previous
                    frames++
                }
                previous = frameTimeNanos
                if (frames < intervals.size) {
                    choreographer.postFrameCallback(this)
                    return
                }
                intervals.sort()
                val median = intervals[intervals.size / 2]
                if (median <= 0L) return
                val fps = 1_000_000_000.0 / median
                Log.i(TAG, "measured display rate: %.2f fps (median interval %dns)".format(fps, median))
                onMeasured(fps.toFloat())
            }
        }
        choreographer.postFrameCallback(callback)
    }


    fun applyContentFrameRate(fps: Float) {
        contentFrameRate = fps
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = attachedSurface ?: holder.surface ?: return
        if (!surface.isValid) return
        val requested = if (fps > 1f && fps < 1000f) fps else 0f
        runCatching {
            // DEFAULT, not FIXED_SOURCE: the latter asks for a mode that is an exact multiple of
            // the content rate, and no panel mode is an exact multiple of 75 or 93, so the platform
            // gives up and composites at 60 - a third of every high-frame-rate file is dropped with
            // an uneven cadence. DEFAULT lets it pick the closest mode (90 on this panel).
            surface.setFrameRate(requested, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            Log.i(TAG, "setFrameRate: $requested")
        }.onFailure { Log.w(TAG, "setFrameRate($requested) failed: ${it.message}") }
    }


    private fun lockBufferSize(holder: SurfaceHolder, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
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
        engine?.let { target -> measureDisplayRate { fps -> target.onDisplayRefreshRateMeasured(fps) } }
        if (engine != null) {
            engine?.attachSurface(surface)
        } else {
            GyroNative.attachSurface(surface)
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
        val surface = holder.surface
        if (surface != null && surface.isValid && attachedSurface !== surface) {
            Log.i(TAG, "onAttachedToWindow -> re-attach existing surface")
            attach(surface)
        }
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
        attachedSurface = null
        Log.i(TAG, "surfaceDestroyed -> detachSurface")
        if (engine != null) {
            engine?.detachSurface()
        } else {
            GyroNative.detachSurface()
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
            surfaceView.engine?.detachSurface() ?: GyroNative.detachSurface()
        }
    }

    val frameRate by (engine?.videoFrameRate ?: remember { MutableStateFlow(0f) }).collectAsState()
    LaunchedEffect(frameRate) { surfaceView.applyContentFrameRate(frameRate) }

    // Pin the buffer to the video's own display size the moment the engine knows it. From then on
    // window resizes never touch the BufferQueue again; the layout layer keeps the view's aspect
    // equal to this one. Long edges are clamped to the screen so a 4K file does not force the
    // compositor to drag a buffer larger than anything it will ever show.
    val videoDimensions by (engine?.videoDimensions ?: remember { MutableStateFlow(VideoDimensions()) }).collectAsState()
    LaunchedEffect(videoDimensions.width, videoDimensions.height) {
        if (videoDimensions.width <= 0 || videoDimensions.height <= 0) return@LaunchedEffect
        val metrics = context.resources.displayMetrics
        val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val scale = min(1f, maxDim / maxOf(videoDimensions.width, videoDimensions.height))
        surfaceView.lockBufferSizeToVideo(
            (videoDimensions.width * scale).toInt().coerceAtLeast(1),
            (videoDimensions.height * scale).toInt().coerceAtLeast(1)
        )
    }

    AndroidView(
        factory = { surfaceView },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}
