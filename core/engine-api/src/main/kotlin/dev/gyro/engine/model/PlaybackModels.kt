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

enum class TrackType {
    VIDEO,
    AUDIO,
    SUBTITLE,
    SECONDARY_SUBTITLE,
    UNKNOWN
}

data class TrackInfo(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val lang: String?,
    val isSelected: Boolean
)

enum class ResizeMode {
    FIT,
    CROP,
    STRETCH,
    ASPECT_16_9,
    ASPECT_4_3
}
