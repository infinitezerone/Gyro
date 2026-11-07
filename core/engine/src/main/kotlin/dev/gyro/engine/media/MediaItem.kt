package dev.gyro.engine.media

import android.net.Uri

/**
 * One load request, immutable.
 *
 * The title is resolved by the caller - a content resolver display name, a playlist entry, or a
 * media store row - because the engine must not know where metadata comes from. When it is absent
 * the engine falls back to the URI's last path segment.
 */
data class MediaItem(
    val uri: Uri? = null,
    val title: String? = null,
    val startPositionMs: Long = 0L,
    val initialDurationMs: Long = 0L,
    val target: String = uri?.toString().orEmpty()
) {
    constructor(
        target: String,
        title: String? = null,
        startPositionMs: Long = 0L,
        initialDurationMs: Long = 0L
    ) : this(
        uri = null,
        title = title,
        startPositionMs = startPositionMs,
        initialDurationMs = initialDurationMs,
        target = target
    )
}
