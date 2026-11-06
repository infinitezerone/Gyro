package dev.gyro.engine.model

/**
 * Where the engine itself is in a load request.
 *
 * This is *our* orchestration state, not a mirror of mpv's playback state: mpv has no idea whether
 * a descriptor is being resolved, whether a surface is missing, or whether a request failed. Making
 * it explicit replaces the pending-source/flag bookkeeping and lets the UI tell "still resolving"
 * from "waiting for a surface" from "mpv never accepted the file".
 *
 * Kept as plain strings/values rather than a `MediaItem`: the direct-path loader receives a target
 * string, and unit tests must not have to construct framework objects to read a phase.
 */
sealed interface SessionPhase {
    data object Idle : SessionPhase
    data class Resolving(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class AwaitingSurface(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class Submitting(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class Loaded(val target: String) : SessionPhase
    data class Failed(val target: String?, val cause: Throwable) : SessionPhase
}
