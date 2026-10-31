package dev.gyro.engine.model

enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class VideoDimensions(
    val width: Int = 0,
    val height: Int = 0
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

data class TrackInfo(
    val id: Int,
    val type: String, // "video", "audio", "sub"
    val title: String?,
    val lang: String?,
    val isSelected: Boolean
)

enum class ResizeMode(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    STRETCH("Stretch"),
    ASPECT_16_9("16:9"),
    ASPECT_21_9("21:9")
}

data class PerformanceStats(
    val fps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val powerWatts: Double = 0.0,
    val currentMa: Int = 0,
    val hwdec: String = ""
)
