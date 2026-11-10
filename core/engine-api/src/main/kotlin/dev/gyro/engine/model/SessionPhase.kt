package dev.gyro.engine.model

/**
 * Where the engine itself is in a load request.
 */
sealed interface SessionPhase {
    data object Idle : SessionPhase
    data class Resolving(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class AwaitingSurface(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class Submitting(val target: String, val startPositionMs: Long = 0L) : SessionPhase
    data class Loaded(val target: String) : SessionPhase
    data class Failed(val target: String?, val cause: Throwable) : SessionPhase
}
