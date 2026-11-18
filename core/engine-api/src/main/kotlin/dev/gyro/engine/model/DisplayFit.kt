package dev.gyro.engine.model

import kotlin.math.abs
import kotlin.math.roundToInt

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
fun computeDisplayFittedBufferSize(
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

/**
 * Normalizes content frame rate to standard broadcast/digital video nominal rates
 * if it falls within a 3.5% tolerance window. Returns the nominal rate if matched,
 * or the contentFps itself if > 1f, or 0f if non-positive.
 */
fun normalizeContentFps(contentFps: Float): Float {
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
