package dev.gyro.feature.player

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.gyro.core.designsystem.R
import dev.gyro.engine.ui.GyroSurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI state controller coordinating hardware-frame capture, shutter flash/haptics, and persistence
 * via a main-safe screenshot callback without manual dispatcher switching in the UI layer.
 */
@Stable
class PlayerScreenshotController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: View,
    private val onSaveScreenshot: suspend (Bitmap, String, Long) -> Boolean,
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

        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        isFlashActive = true

        scope.launch {
            try {
                val bitmap = surfaceView.captureBitmap()
                val saved = if (bitmap != null) {
                    try {
                        onSaveScreenshot(bitmap, mediaTitle, positionMs)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    false
                }
                Toast.makeText(
                    context,
                    context.getString(if (saved) R.string.screenshot_saved else R.string.screenshot_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                isCapturing.set(false)
            }
        }
    }
}

@Composable
fun rememberPlayerScreenshotController(
    onSaveScreenshot: suspend (Bitmap, String, Long) -> Boolean,
    context: Context = LocalContext.current,
    scope: CoroutineScope = rememberCoroutineScope(),
    view: View = LocalView.current,
): PlayerScreenshotController {
    val saveState = rememberUpdatedState(onSaveScreenshot)
    return remember(context, scope, view) {
        PlayerScreenshotController(
            context = context,
            scope = scope,
            view = view,
            onSaveScreenshot = { bitmap, title, pos -> saveState.value(bitmap, title, pos) },
        )
    }
}

@Composable
fun PlayerShutterFlashOverlay(
    controller: PlayerScreenshotController,
    modifier: Modifier = Modifier,
) {
    val flashAlpha by animateFloatAsState(
        targetValue = if (controller.isFlashActive) 0.60f else 0f,
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        finishedListener = { if (controller.isFlashActive) controller.isFlashActive = false },
        label = "shutterFlash",
    )
    if (flashAlpha > 0.01f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha))
        )
    }
}
