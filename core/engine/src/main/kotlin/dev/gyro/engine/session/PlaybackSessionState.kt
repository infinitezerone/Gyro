package dev.gyro.engine.session

import dev.gyro.engine.model.VideoDimensions

/**
 * Algebraic data type representing the lifecycle state of a playback session.
 * Eliminates boolean blindness and invalid state combinations across the player pipeline.
 */
sealed interface PlaybackSessionState {
    /** Engine is idle or stopped. No media active. */
    data object Idle : PlaybackSessionState

    /** Media is loading/switching. Stale VO properties from previous playback must be ignored. */
    data class Switching(
        val url: String,
        val startPositionMs: Long = 0L,
    ) : PlaybackSessionState

    /** Demuxer parsed track headers (FILE_LOADED). Container geometry is known; awaiting decoder first frame. */
    data class Demuxed(
        val url: String,
        val dimensions: VideoDimensions,
        val aspect: Float,
    ) : PlaybackSessionState

    /** First video frame has been decoded and presented. Normal playback active; dynamic ABR updates accepted. */
    data class Presenting(
        val url: String,
        val dimensions: VideoDimensions,
        val aspect: Float,
    ) : PlaybackSessionState

    /** Playback reached end of file or stopped. */
    data object Ended : PlaybackSessionState
}
