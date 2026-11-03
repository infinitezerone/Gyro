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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** A buffer only counts as a different aspect when it is off by more than integer rounding in a 16:9 box. */
private const val ASPECT_EPSILON = 0.01

/** A PiP transition can resize the window several frames in a row; re-lock once it settles. */
private const val RELOCK_DEBOUNCE_MS = 80L

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
 * It also owns one policy that the renderer cannot: the buffer size. The buffer is pinned with
 * [SurfaceHolder.setFixedSize] and only re-locked when the aspect ratio changes or the view grows,
 * so a PiP transition moves the window without re-rendering at a new size. Letting the buffer follow
 * every window change puts the re-layout 40-70ms behind the window animation, which is the hop and
 * the black gap seen at both ends of a PiP transition.
 */
class GyroSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "GyroSurfaceView"

        /**
         * True while the activity is entering or leaving PiP.
         *
         * Relocking during the transition is a needless re-layout: the aspect does not change, and
         * the re-layout is what lands late. Set from the PiP state the activity already tracks.
         */
        @Volatile
        var pipTransitionActive: Boolean = false
    }

    var engine: GyroPlayerEngine? = null

    /** Buffer size currently fixed on the holder; 0 until the first lock. */
    private var lockedWidth = 0
    private var lockedHeight = 0
    private var pendingWidth = 0
    private var pendingHeight = 0

    private val relockRunnable = Runnable {
        if (pendingWidth > 0 && pendingHeight > 0 && shouldRelock(pendingWidth, pendingHeight)) {
            lockBufferSize(holder, pendingWidth, pendingHeight)
        }
    }

    /** Frame rate of the content on screen, 0 when the system should pick. */
    private var contentFrameRate = 0f

    /** Surface whose window the engine currently holds, for the ownership check in `surfaceDestroyed`. */
    private var attachedSurface: Surface? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lockBufferSize(holder, width, height)
        Log.i(TAG, "surfaceCreated -> attachSurface (engine=$engine, buffer=${lockedWidth}x$lockedHeight)")
        attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: window=${width}x$height buffer=${lockedWidth}x$lockedHeight")
        if (lockedWidth <= 0 || lockedHeight <= 0) lockBufferSize(holder, width, height)

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "viewSize ${oldw}x$oldh -> ${w}x$h (locked ${lockedWidth}x$lockedHeight)")
        if (!shouldRelock(w, h)) return
        pendingWidth = w
        pendingHeight = h
        removeCallbacks(relockRunnable)
        postDelayed(relockRunnable, RELOCK_DEBOUNCE_MS)
    }

    /**
     * Whether a new view size justifies re-locking the buffer.
     *
     * - A different aspect ratio always does: `SurfaceView` maps a buffer onto the view with
     *   independent x and y scaling, so a buffer of the wrong aspect is stretched, and no renderer
     *   can correct that.
     * - With the aspect unchanged only a larger area is worth more pixels. PiP only ever shrinks,
     *   so it never re-locks, which is what keeps the transition on one buffer.
     */
    private fun shouldRelock(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (lockedWidth <= 0 || lockedHeight <= 0) return true
        // An aspect change always wins, transition or not: this view fills the screen, so the PiP
        // window (video aspect) really is a different shape from the portrait full-screen surface,
        // and a buffer of the wrong shape is stretched by the compositor with no way back.
        val lockedAspect = lockedWidth.toDouble() / lockedHeight
        if (abs(w.toDouble() / h - lockedAspect) > lockedAspect * ASPECT_EPSILON) return true
        // Same shape: growing back out of PiP is not a quality need, it is the re-layout that makes
        // the exit hop, so it waits until the transition is over.
        if (pipTransitionActive) return false
        return w.toLong() * h > lockedWidth.toLong() * lockedHeight
    }

    /**
     * Tells the platform how fast the video runs, so it can composite at a matching rate.
     *
     * Without this the surface is composited at the default rate and high-frame-rate content is
     * decimated with an irregular cadence - measured 60.2fps out of a 75fps file on a 90Hz panel,
     * which is what reads as smearing in motion. Same call media3's `MediaCodecVideoRenderer` makes
     * on API 30+.
     */
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
        pendingWidth = 0
        pendingHeight = 0
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

    override fun onDetachedFromWindow() {
        removeCallbacks(relockRunnable)
        super.onDetachedFromWindow()
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

    AndroidView(
        factory = { surfaceView },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}
