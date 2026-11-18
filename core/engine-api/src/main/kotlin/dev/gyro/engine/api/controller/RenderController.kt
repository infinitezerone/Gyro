package dev.gyro.engine.api.controller

import android.view.Surface
import dev.gyro.engine.model.HdrDisplayCapabilities

/**
 * Controller interface governing the Android video surface binding and compositor redraws.
 *
 * Isolates surface attachment, detachment, EGL rebuilds, and manual repaints from playback
 * queue/timeline orchestration.
 */
interface RenderController {
    val isSurfaceAttached: Boolean
    val isFirstFrameRendered: Boolean
        get() = false

    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun detachSurface(surface: Surface?)
    fun redraw()
    fun requestSurfaceRebuild()
    fun updateDisplayRefreshRate(rate: Float) {}

    /**
     * Whole-panel capability table: supported HDR types plus peak luminance.
     *
     * The output decision needs both at once. Reporting them separately forced the engine to
     * re-decide twice and made "supports HDR" indistinguishable from "understands HLG".
     */
    fun setDisplayCapabilities(capabilities: HdrDisplayCapabilities) {}
}
