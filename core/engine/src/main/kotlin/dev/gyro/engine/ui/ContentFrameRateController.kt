package dev.gyro.engine.ui

import android.os.Build
import android.view.Surface
import dev.gyro.engine.trace.EngineLog

/**
 * Votes the display's refresh rate towards the playing content's frame rate.
 *
 * Votes on the Surface itself (Android 11+). Uses CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS so the
 * display compositor adapts smoothly without disruptive hardware mode switches, flickering, or
 * panel clock resyncs. The vote must be re-applied on every fresh surface: a new surface starts
 * at the platform default rate, so the last content rate is replayed on attach.
 *
 * [currentSurface] is provided by the owning view so the controller stays decoupled from the
 * SurfaceView lifecycle while always voting on the surface the engine actually holds.
 */
internal class ContentFrameRateController(
    private val currentSurface: () -> Surface?
) {
    private var contentFrameRate = 0f

    /** Applies [fps], normalizing it to a nominal broadcast rate within the 3.5% window. */
    fun apply(fps: Float) {
        EngineLog.i(TAG, "applyContentFrameRate: ENTER fps=$fps (current contentFrameRate=$contentFrameRate)")
        contentFrameRate = fps
        val requested = if (fps > 1f && fps < 1000f) fps else 0f
        val normalized = normalizeContentFps(requested)
        vote(normalized)
    }

    /** Re-votes the last applied rate - used when a fresh surface replaces the old one. */
    fun reapplyCurrent() = apply(contentFrameRate)

    private fun vote(normalized: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = currentSurface()
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

    private companion object {
        const val TAG = "GyroSurfaceView"
    }
}
