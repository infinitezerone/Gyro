package dev.gyro.core.domain.model

import android.net.Uri

/**
 * Read-only domain projection of the active playback session state.
 */
data class PlaybackSessionInfo(
    val isActive: Boolean = false,
    val activeUri: Uri? = null,
)
