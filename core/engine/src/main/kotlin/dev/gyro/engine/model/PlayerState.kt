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
