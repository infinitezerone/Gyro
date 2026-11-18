package dev.gyro.feature.player.surface

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val CAPTURE_TAG = "GyroSurfaceView"

/**
 * Coordinates a bounded asynchronous pixel copy into [bitmap], ensuring [recycle] is never called
 * while the producer (such as [PixelCopy]) may still hold the buffer, and preventing
 * `Already resumed` or post-cancellation `resume` crashes.
 */
internal suspend fun <T : Any> awaitSurfaceCopy(
    bitmap: T,
    timeoutMs: Long = 2000L,
    recycle: (T) -> Unit,
    onSuccess: (T) -> Unit = {},
    requestCopy: (target: T, onFinished: (copyResult: Int) -> Unit) -> Unit,
): T? {
    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) {
                recycle(bitmap)
                return@suspendCancellableCoroutine
            }
            val callbackConsumed = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                // Never recycle here: PixelCopy may still be writing until its callback fires.
                SurfaceLog.w(CAPTURE_TAG, "captureBitmap PixelCopy cancelled or timed out")
            }
            try {
                requestCopy(bitmap) { copyResult ->
                    if (!callbackConsumed.compareAndSet(false, true)) return@requestCopy
                    if (!continuation.isActive) {
                        SurfaceLog.w(
                            CAPTURE_TAG,
                            "captureBitmap PixelCopy finished after cancellation/timeout: code=$copyResult",
                        )
                        recycle(bitmap)
                    } else if (copyResult == PixelCopy.SUCCESS) {
                        onSuccess(bitmap)
                        continuation.resume(bitmap) { _, value, _ ->
                            recycle(value)
                        }
                    } else {
                        SurfaceLog.w(CAPTURE_TAG, "captureBitmap PixelCopy failed: code=$copyResult")
                        recycle(bitmap)
                        continuation.resume(null)
                    }
                }
            } catch (e: Throwable) {
                SurfaceLog.e(CAPTURE_TAG, "captureBitmap PixelCopy.request exception", e)
                if (callbackConsumed.compareAndSet(false, true)) {
                    recycle(bitmap)
                }
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }
}

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
): Bitmap? {
    if (surface == null || !surface.isValid || viewWidth <= 0 || viewHeight <= 0) {
        SurfaceLog.w(CAPTURE_TAG, "captureBitmap aborted: surface valid=${surface?.isValid}, size=${viewWidth}x${viewHeight}")
        return null
    }
    val targetW = if (bufferWidth > 0) bufferWidth else viewWidth
    val targetH = if (bufferHeight > 0) bufferHeight else viewHeight
    val bitmap = try {
        Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        SurfaceLog.e(CAPTURE_TAG, "captureBitmap Bitmap.createBitmap failed (target=${targetW}x$targetH)", t)
        return null
    }
    return awaitSurfaceCopy(
        bitmap = bitmap,
        timeoutMs = 2000L,
        recycle = { it.recycle() },
        onSuccess = { captured ->
            SurfaceLog.i(CAPTURE_TAG, "captureBitmap PixelCopy SUCCESS: ${captured.width}x${captured.height}")
        },
        requestCopy = { targetBitmap, onFinished ->
            val handler = Handler(Looper.getMainLooper())
            PixelCopy.request(
                surface,
                null,
                targetBitmap,
                { copyResult -> onFinished(copyResult) },
                handler
            )
        }
    )
}
