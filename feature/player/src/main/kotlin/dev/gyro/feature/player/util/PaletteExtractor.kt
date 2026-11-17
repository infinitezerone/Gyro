package dev.gyro.feature.player.util

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.gyro.core.model.MediaPalette

/**
 * Extracted dynamic palette colors from media poster/frame.
 */
data class PlayerPaletteColors(
    val dominantColor: Color = Color.Transparent,
    val accentColor: Color = Color(MediaPalette.DEFAULT_ACCENT_ARGB),
    val darkMutedColor: Color = Color(MediaPalette.DEFAULT_DARK_MUTED_ARGB),
)

@Composable
fun rememberPlayerPalette(
    bitmap: Bitmap?,
    extractPalette: suspend (Bitmap?) -> MediaPalette,
): PlayerPaletteColors {
    val extractPaletteState = rememberUpdatedState(extractPalette)
    var paletteColors by remember(bitmap) {
        mutableStateOf(PlayerPaletteColors())
    }

    LaunchedEffect(bitmap) {
        if (bitmap == null || bitmap.isRecycled) {
            paletteColors = PlayerPaletteColors()
            return@LaunchedEffect
        }
        val mediaPalette = extractPaletteState.value(bitmap)
        paletteColors = PlayerPaletteColors(
            dominantColor = mediaPalette.dominantArgb?.let(::Color) ?: Color.Transparent,
            accentColor = Color(mediaPalette.accentArgb),
            darkMutedColor = Color(mediaPalette.darkMutedArgb),
        )
    }

    return paletteColors
}
