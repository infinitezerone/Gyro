package dev.gyro.feature.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Immutable snapshot of scrub progress to prevent invalid intermediate states.
 */
data class PlayerScrubSnapshot(
    val active: Boolean = false,
    val fraction: Float = 0f,
    val isPreciseSeeking: Boolean = false,
    val originTimeMs: Long = 0L,
    val wasPlayingBeforePreciseSeek: Boolean = false
)

/**
 * Single source of truth for scrub/seek state in the Gyro player.
 * Both horizontal on-screen gestures and the bottom scrub bar read and write via this holder.
 * Leaf composables read from this state to avoid recomposing the entire controls overlay.
 *
 * In YouTube-style Precise Seeking mode:
 * - Entering precise seek pauses video and remembers origin position & playing status.
 * - When finger releases, precise seeking mode REMAINS OPEN, keeping the video paused and
 *   holding the filmstrip visible until user confirms (Resume) or cancels.
 */
@Stable
class PlayerScrubState {
    var state by mutableStateOf(PlayerScrubSnapshot())
        private set

    val isActive: Boolean
        get() = state.active

    val fraction: Float
        get() = state.fraction

    val isPreciseSeeking: Boolean
        get() = state.isPreciseSeeking

    val originTimeMs: Long
        get() = state.originTimeMs

    val wasPlayingBeforePreciseSeek: Boolean
        get() = state.wasPlayingBeforePreciseSeek

    fun begin(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        state = state.copy(
            active = true,
            fraction = clamped
        )
    }

    fun update(fraction: Float) {
        if (!state.active && !state.isPreciseSeeking) return
        state = state.copy(fraction = fraction.coerceIn(0f, 1f))
    }

    fun enterPreciseSeeking(originMs: Long, wasPlaying: Boolean) {
        state = state.copy(
            isPreciseSeeking = true,
            originTimeMs = originMs,
            wasPlayingBeforePreciseSeek = wasPlaying
        )
    }

    fun exitPreciseSeeking() {
        state = state.copy(isPreciseSeeking = false)
    }

    fun end(fraction: Float) {
        // Preserves isPreciseSeeking so the panel stays open after finger release!
        state = state.copy(active = false, fraction = fraction.coerceIn(0f, 1f))
    }

    fun cancel() {
        state = PlayerScrubSnapshot(active = false, fraction = 0f, isPreciseSeeking = false)
    }
}
