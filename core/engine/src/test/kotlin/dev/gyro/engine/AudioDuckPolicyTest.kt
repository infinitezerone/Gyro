package dev.gyro.engine

import dev.gyro.engine.audio.AudioDuckPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ducking is the only place where the player touches the system stream behind the user's back, so
 * the arithmetic is pinned down here instead of relying on a device to reveal it.
 */
class AudioDuckPolicyTest {

    @Test
    fun duckTarget_appliesFactorToCurrentStep() {
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15))
        assertEquals(5, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = 0.5f))
    }

    @Test
    fun duckTarget_neverMutesContent() {
        // A duck must lower the volume, not silence the video behind the user's back.
        assertEquals(1, AudioDuckPolicy.duckTarget(current = 1, max = 15))
        assertEquals(1, AudioDuckPolicy.duckTarget(current = 2, max = 15))
        assertEquals(1, AudioDuckPolicy.duckTarget(current = 3, max = 15, factor = 0.1f))
    }

    @Test
    fun duckTarget_returnsCurrentWhenThereIsNoHeadroom() {
        assertEquals(0, AudioDuckPolicy.duckTarget(current = 0, max = 15))
        assertEquals(10, AudioDuckPolicy.duckTarget(current = 10, max = 0))
        // Nonsensical input is passed through untouched, so the caller's "target >= current" skip
        // still holds instead of inventing a volume out of a negative step.
        assertEquals(-3, AudioDuckPolicy.duckTarget(current = -3, max = 15))
    }

    @Test
    fun duckTarget_fallsBackOnInvalidFactor() {
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = Float.NaN))
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = 0f))
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = -1f))
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = 1.5f))
        assertEquals(4, AudioDuckPolicy.duckTarget(current = 10, max = 15, factor = 1f))
    }

    @Test
    fun duckTarget_neverExceedsCurrentAcrossTheWholeRange() {
        for (max in intArrayOf(1, 5, 15, 25, 100)) {
            for (current in 0..max) {
                val target = AudioDuckPolicy.duckTarget(current, max)
                assertTrue("target $target > current $current (max=$max)", target <= current)
                assertTrue("target $target < 0 (current=$current)", target >= 0)
            }
        }
    }

    @Test
    fun shouldRestoreVolume_onlyWhenVolumeIsUntouched() {
        assertTrue(AudioDuckPolicy.shouldRestoreVolume(current = 4, duckedTo = 4))
        assertFalse(AudioDuckPolicy.shouldRestoreVolume(current = 7, duckedTo = 4))
        assertFalse(AudioDuckPolicy.shouldRestoreVolume(current = 0, duckedTo = 4))
        assertFalse(AudioDuckPolicy.shouldRestoreVolume(current = -1, duckedTo = 4))
    }
}
