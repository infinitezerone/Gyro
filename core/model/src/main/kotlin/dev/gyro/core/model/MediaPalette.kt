package dev.gyro.core.model

/**
 * Dynamic Material color swatches (ARGB ints) extracted from a media poster or video frame.
 */
data class MediaPalette(
    val dominantArgb: Int? = null,
    val accentArgb: Int = DEFAULT_ACCENT_ARGB,
    val darkMutedArgb: Int = DEFAULT_DARK_MUTED_ARGB,
) {
    companion object {
        const val DEFAULT_ACCENT_ARGB: Int = 0xFF6750A4.toInt()
        const val DEFAULT_DARK_MUTED_ARGB: Int = 0xFF14171F.toInt()
    }
}
