package dev.gyro.engine.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val CAPTURE_TAG = "GyroSurfaceView"

/**
 * Captures the current visible frame directly from the hardware [Surface]
 * using [PixelCopy], completely bypassing decoder memory access restrictions
 * (such as zero-copy MediaCodec hardware decoding).
 *
 * [bufferWidth]/[bufferHeight] win over [viewWidth]/[viewHeight] when set: PixelCopy copies the
 * BufferQueue's backing resolution, so the bitmap must match the applied buffer, not the view.
 * Bounded by a 2s timeout - a wedged compositor must not hang the caller forever.
 */
internal suspend fun captureSurfaceBitmap(
    surface: Surface?,
    viewWidth: Int,
    viewHeight: Int,
    bufferWidth: Int,
    bufferHeight: Int
): Bitmap? = withContext(Dispatchers.Main) {
    if (surface == null || !surface.isValid || viewWidth <= 0 || viewHeight <= 0) {
        EngineLog.w(CAPTURE_TAG, "captureBitmap aborted: surface valid=${surface?.isValid}, size=${viewWidth}x${viewHeight}")
        return@withContext null
    }
    val targetW = if (bufferWidth > 0) bufferWidth else viewWidth
    val targetH = if (bufferHeight > 0) bufferHeight else viewHeight
    val bitmap = try {
        Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        EngineLog.e(CAPTURE_TAG, "captureBitmap Bitmap.createBitmap failed (target=${targetW}x$targetH)", t)
        return@withContext null
    }
    withTimeoutOrNull(2000L) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            try {
                PixelCopy.request(
                    surface,
                    null,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            EngineLog.i(CAPTURE_TAG, "captureBitmap PixelCopy SUCCESS: ${bitmap.width}x${bitmap.height}")
                            continuation.resume(bitmap)
                        } else {
                            EngineLog.w(CAPTURE_TAG, "captureBitmap PixelCopy failed: code=$copyResult")
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    },
                    handler
                )
            } catch (e: Throwable) {
                EngineLog.e(CAPTURE_TAG, "captureBitmap PixelCopy.request exception", e)
                bitmap.recycle()
                continuation.resume(null)
            }
        }
    } ?: run {
        EngineLog.w(CAPTURE_TAG, "captureBitmap PixelCopy timed out after 2000ms")
        bitmap.recycle()
        null
    }
}
