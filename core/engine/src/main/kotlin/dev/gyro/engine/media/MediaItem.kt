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
    val uri: Uri,
    val title: String? = null,
    val startPositionMs: Long = 0L
)
