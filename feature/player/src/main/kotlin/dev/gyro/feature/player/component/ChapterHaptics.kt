package dev.gyro.feature.player.component

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A single, very short tick used when the scrubber crosses a chapter marker.
 *
 * Kept deliberately faint: this fires many times while scrubbing a chaptered file, so it should
 * read as a texture under the finger rather than a notification. EFFECT_TICK is the platform's
 * lightest predefined effect; the amplitude fallback covers older devices.
 */
internal class ChapterHaptics(private val vibrate: (Long) -> Unit) {
    fun tick() = vibrate(TICK_MS)

    private companion object {
        const val TICK_MS = 8L
    }
}

@Composable
internal fun rememberChapterHaptics(): ChapterHaptics {
    val context = LocalContext.current
    return remember(context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        ChapterHaptics { duration ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(duration, 60))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(duration)
            }
        }
    }
}
