package dev.gyro.engine.api.media

import android.net.Uri

/**
 * One load request, immutable.
 */
data class MediaItem(
    val uri: Uri? = null,
    val title: String? = null,
    val startPositionMs: Long = 0L,
    val initialDurationMs: Long = 0L,
    val initialWidth: Int = 0,
    val initialHeight: Int = 0,
    val target: String = uri?.toString().orEmpty()
) {
    constructor(
        target: String,
        title: String? = null,
        startPositionMs: Long = 0L,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) : this(
        uri = null,
        title = title,
        startPositionMs = startPositionMs,
        initialDurationMs = initialDurationMs,
        initialWidth = initialWidth,
        initialHeight = initialHeight,
        target = target
    )
}
