package dev.gyro.engine.model

/**
 * Telemetry that changes while a file plays: dropped frames, presented fps, vsync ratio and
 * instantaneous bitrates.
 *
 * Deliberately not part of [PlaybackSnapshot]. These values tick on their own schedule (mpv
 * reports them per second, sometimes faster), and folding them into the playback snapshot made
 * every tick invalidate every consumer of playback state - the whole player screen recomposed
 * because one dropped-frame counter moved.
 *
 * Consumers that render diagnostics subscribe to this flow; consumers that drive layout and
 * transport subscribe to [PlaybackSnapshot]. The split is by change frequency, not by ownership:
 * both are still written by the same engine on the same dispatcher.
 */
data class PlaybackDiagnostics(
    val fps: Double = 0.0,
    val droppedFrames: Long = 0L,
    val vsyncRatio: Double = 0.0,
    val videoBitrate: Long = 0L,
    val audioBitrate: Long = 0L,
)
