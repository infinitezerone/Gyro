package dev.gyro.feature.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.gyro.core.designsystem.R
import dev.gyro.engine.ui.GyroSurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates the complete screenshot capture pipeline:
 * - Direct lossless hardware buffer extraction via [GyroSurfaceView.captureBitmap];
 * - Anti-spam concurrency throttle via [AtomicBoolean];
 * - Instant haptic tap & camera shutter flash feedback;
 * - Android 15 compliant MediaStore persistence with full date/dimension metadata;
 * - Scoped app-private fallback storage if MediaStore insert fails;
 * - User-facing Toast notifications.
 */
@Stable
class PlayerScreenshotController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: View,
) {
    private val isCapturing = AtomicBoolean(false)
    var isFlashActive by mutableStateOf(false)
        internal set

    fun capture(
        surfaceView: GyroSurfaceView?,
        mediaTitle: String,
        positionMs: Long,
    ) {
        if (surfaceView == null) return
        if (!isCapturing.compareAndSet(false, true)) return

        // 1. 瞬时触觉与快门视觉反馈
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        isFlashActive = true

        // 2. 语义化文件名
        val nowMs = System.currentTimeMillis()
        val filename = buildFilename(mediaTitle, positionMs, nowMs)

        // 3. 异步落盘管线
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = surfaceView.captureBitmap() ?: run {
                    notifyResult(saved = false)
                    return@launch
                }
                val saved = saveToMediaStore(context, bitmap, filename, nowMs) ||
                    saveToAppStorage(context, bitmap, filename)
                bitmap.recycle()
                notifyResult(saved)
            } finally {
                isCapturing.set(false)
            }
        }
    }

    private suspend fun notifyResult(saved: Boolean) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.screenshot_saved else R.string.screenshot_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    internal companion object {
        private const val TAG = "PlayerScreenshot"
        internal val ILLEGAL_CHAR_REGEX = Regex("""[\\/:*?"<>|\s]""")

        internal fun buildFilename(mediaTitle: String, posMs: Long, timestampMs: Long): String {
            val safeTitle = mediaTitle.ifBlank { "Snapshot" }
                .replace(ILLEGAL_CHAR_REGEX, "_")
                .take(30)
            val posStr = formatPosition(posMs)
            return "Gyro_${safeTitle}_${posStr}_${timestampMs}.jpg"
        }

        internal fun formatPosition(posMs: Long): String {
            val hours = posMs / 3600000L
            val minutes = (posMs % 3600000L) / 60000L
            val seconds = (posMs % 60000L) / 1000L
            return if (hours > 0) {
                String.format(Locale.US, "%02dh%02dm%02ds", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02dm%02ds", minutes, seconds)
            }
        }

        private fun saveToMediaStore(
            context: Context,
            bitmap: Bitmap,
            filename: String,
            nowMs: Long
        ): Boolean = runCatching {
            val nowSec = nowMs / 1000L
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
                put(MediaStore.Images.Media.DATE_ADDED, nowSec)
                put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
                put(MediaStore.Images.Media.WIDTH, bitmap.width)
                put(MediaStore.Images.Media.HEIGHT, bitmap.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gyro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.onFailure {
            Log.e(TAG, "saveToMediaStore failed", it)
        }.getOrDefault(false)

        private fun saveToAppStorage(
            context: Context,
            bitmap: Bitmap,
            filename: String
        ): Boolean = runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
            val file = File(dir, filename)
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            Log.i(TAG, "saveToAppStorage succeeded: ${file.absolutePath}")
            true
        }.onFailure {
            Log.e(TAG, "saveToAppStorage failed", it)
        }.getOrDefault(false)
    }
}

/**
 * Creates and remembers a [PlayerScreenshotController] attached to the local composition context.
 */
@Composable
fun rememberPlayerScreenshotController(
    context: Context = LocalContext.current,
    scope: CoroutineScope = rememberCoroutineScope(),
    view: View = LocalView.current,
): PlayerScreenshotController {
    return remember(context, scope, view) {
        PlayerScreenshotController(context, scope, view)
    }
}

/**
 * Renders the instant camera shutter white flash feedback overlay.
 */
@Composable
fun PlayerShutterFlashOverlay(
    controller: PlayerScreenshotController,
    modifier: Modifier = Modifier,
) {
    val flashAlpha by animateFloatAsState(
        targetValue = if (controller.isFlashActive) 0.60f else 0f,
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        finishedListener = { if (controller.isFlashActive) controller.isFlashActive = false },
        label = "shutterFlash"
    )
    if (flashAlpha > 0.01f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha))
        )
    }
}
