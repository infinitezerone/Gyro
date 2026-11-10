package dev.gyro.feature.player.util

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracted dynamic palette colors from media poster/frame using Jetpack Palette.
 */
data class PlayerPaletteColors(
    val dominantColor: Color = Color.Transparent,
    val accentColor: Color = Color(0xFF6750A4),
    val darkMutedColor: Color = Color(0xFF14171F)
)

@Composable
fun rememberPlayerPalette(bitmap: Bitmap?): PlayerPaletteColors {
    var paletteColors by remember(bitmap) {
        mutableStateOf(PlayerPaletteColors())
    }

    LaunchedEffect(bitmap) {
        if (bitmap == null || bitmap.isRecycled) {
            paletteColors = PlayerPaletteColors()
            return@LaunchedEffect
        }

        withContext(Dispatchers.Default) {
            try {
                val palette = Palette.from(bitmap)
                    .maximumColorCount(16)
                    .generate()

                val dominant = palette.dominantSwatch?.rgb?.let { Color(it) } ?: Color.Transparent
                val vibrant = palette.vibrantSwatch?.rgb?.let { Color(it) }
                    ?: palette.lightVibrantSwatch?.rgb?.let { Color(it) }
                    ?: Color(0xFF6750A4)
                val darkMuted = palette.darkMutedSwatch?.rgb?.let { Color(it) }
                    ?: palette.mutedSwatch?.rgb?.let { Color(it) }
                    ?: Color(0xFF14171F)

                paletteColors = PlayerPaletteColors(
                    dominantColor = dominant,
                    accentColor = vibrant,
                    darkMutedColor = darkMuted
                )
            } catch (_: Throwable) {
                // Fallback to default
            }
        }
    }

    return paletteColors
}
