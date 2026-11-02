package dev.gyro.engine.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.gyro.engine.GyroNative
import dev.gyro.engine.GyroPlayerEngine

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

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated -> attachSurface (engine=$engine)")
        if (engine != null) {
            engine?.attachSurface(holder.surface)
        } else {
            GyroNative.attachSurface(holder.surface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: width=$width, height=$height")
        // Rotation, split screen and PiP only resize the window; it survives, so no attach happens.
        // While video is running the next frame reads the new size from eglQuerySurface and repaints
        // itself. A paused player produces no frames at all, so it is the only case that needs to be
        // told to repaint.
        val target = engine
        // A live surface with the native side unbound is the frozen-picture case: `surfaceDestroyed`
        // ran, no `surfaceCreated` followed because the view was reused, and drawing silently stops
        // while audio keeps playing. Re-bind before deciding whether a repaint is needed.
        if (target != null && !target.isSurfaceAttached && holder.surface?.isValid == true) {
            Log.i(TAG, "surfaceChanged -> re-attach unbound surface")
            target.attachSurface(holder.surface)
            return
        }
        if (target != null && target.isPlaying.value) return
        if (target != null) {
            target.redraw()
        } else {
            GyroNative.redraw()
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
        if (surface != null && surface.isValid) {
            Log.i(TAG, "onAttachedToWindow -> re-attach existing surface")
            if (engine != null) {
                engine?.attachSurface(surface)
            } else {
                GyroNative.attachSurface(surface)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
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

    AndroidView(
        factory = { surfaceView },
        update = { view ->
            view.engine = engine
        },
        modifier = modifier
    )
}
