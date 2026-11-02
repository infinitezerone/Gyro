package dev.gyro.engine.audio

import kotlin.math.floor

/**
 * Pure arithmetic for audio ducking, kept out of [GyroAudioFocusManager] so it can be unit tested
 * without an Android runtime.
 *
 * Ducking deliberately operates on the system music stream and never touches mpv's software gain.
 * The player exposes a two-stage volume model (0..100% system stream, 101..200% mpv gain), so
 * lowering the gain here would silently destroy the user's overdrive setting and leave the system
 * stream at maximum. Lowering the stream keeps both layers consistent and restores exactly.
 */
object AudioDuckPolicy {

    /** Roughly -8 dB. Shallow enough to stay audible, deep enough to yield to a navigation prompt. */
    const val DEFAULT_DUCK_FACTOR: Float = 0.4f

    /**
     * Computes the system volume step to duck to.
     *
     * Never returns 0 while [current] is positive: a duck must lower the volume, not mute content
     * behind the user's back. When the computed target would equal [current] (single-step devices,
     * already silent), ducking is pointless and [current] is returned unchanged for the caller to skip.
     */
    fun duckTarget(current: Int, max: Int, factor: Float = DEFAULT_DUCK_FACTOR): Int {
        if (current <= 0 || max <= 0) return current
        val safeFactor = if (factor.isNaN() || factor <= 0f || factor >= 1f) DEFAULT_DUCK_FACTOR else factor
        val raw = floor(current * safeFactor.toDouble()).toInt()
        val lowerBound = 1.coerceAtMost(current)
        return raw.coerceIn(lowerBound, current)
    }

    /**
     * Decides whether a previously ducked volume may be restored.
     *
     * Restoration is refused when the current step no longer matches what ducking applied, because
     * that means the user (or the system) changed the volume in the meantime and overriding it would
     * fight the user.
     */
    fun shouldRestoreVolume(current: Int, duckedTo: Int): Boolean = current == duckedTo
}
