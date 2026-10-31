package dev.gyro.player.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Immutable snapshot of scrub progress to prevent invalid intermediate states.
 */
data class PlayerScrubSnapshot(
    val active: Boolean = false,
    val fraction: Float = 0f
)

/**
 * Single source of truth for scrub/seek state in the Gyro player.
 * Both horizontal on-screen gestures and the bottom scrub bar read and write via this holder.
 * Leaf composables read from this state to avoid recomposing the entire controls overlay.
 */
@Stable
class PlayerScrubState {
    var state by mutableStateOf(PlayerScrubSnapshot())
        private set

    val isActive: Boolean
        get() = state.active

    val fraction: Float
        get() = state.fraction

    fun begin(fraction: Float) {
        state = PlayerScrubSnapshot(active = true, fraction = fraction.coerceIn(0f, 1f))
    }

    fun update(fraction: Float) {
        if (!state.active) return
        state = state.copy(fraction = fraction.coerceIn(0f, 1f))
    }

    fun end(fraction: Float) {
        state = PlayerScrubSnapshot(active = false, fraction = fraction.coerceIn(0f, 1f))
    }

    fun cancel() {
        state = PlayerScrubSnapshot(active = false, fraction = 0f)
    }
}
