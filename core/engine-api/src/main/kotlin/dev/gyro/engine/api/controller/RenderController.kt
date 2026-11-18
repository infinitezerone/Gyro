package dev.gyro.engine.api.controller

import android.view.Surface

/**
 * Controller interface governing the Android video surface binding and compositor redraws.
 *
 * Isolates surface attachment, detachment, EGL rebuilds, and manual repaints from playback
 * queue/timeline orchestration.
 */
interface RenderController {
    val isSurfaceAttached: Boolean

    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun detachSurface(surface: Surface?)
    fun redraw()
    fun requestSurfaceRebuild()
}
