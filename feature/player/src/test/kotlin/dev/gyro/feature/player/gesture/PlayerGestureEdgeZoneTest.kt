package dev.gyro.feature.player.gesture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The edge dead zone is the only thing standing between a diagonal brightness swipe and an
 * accidental exit from the player, but an oversized zone silently kills the swipe gesture itself.
 * These cases pin down both directions of that trade-off.
 */
class PlayerGestureEdgeZoneTest {

    private val base = 60f
    private val ceiling = 120f

    @Test
    fun noGestureNavigation_keepsBaseZone() {
        // 3-button navigation reports no system gesture inset.
        assertEquals(base, edgeDeadZonePx(base, 0f, ceiling), 0.001f)
    }

    @Test
    fun widerPlatformInset_wins() {
        assertEquals(96f, edgeDeadZonePx(base, 96f, ceiling), 0.001f)
    }

    @Test
    fun narrowerPlatformInset_keepsBaseZone() {
        assertEquals(base, edgeDeadZonePx(base, 30f, ceiling), 0.001f)
    }

    @Test
    fun inflatedVendorInset_isCappedByCeiling() {
        // A rogue 300px report must not eat the swipe area on both sides of the screen.
        assertEquals(ceiling, edgeDeadZonePx(base, 300f, ceiling), 0.001f)
    }

    @Test
    fun invalidInputs_fallBackToBaseZone() {
        assertEquals(base, edgeDeadZonePx(base, Float.NaN, ceiling), 0.001f)
        assertEquals(base, edgeDeadZonePx(base, -24f, ceiling), 0.001f)
    }

    @Test
    fun invalidCeiling_neverShrinksBelowBase() {
        assertEquals(base, edgeDeadZonePx(base, 0f, 0f), 0.001f)
        assertEquals(base, edgeDeadZonePx(base, 30f, Float.NaN), 0.001f)
    }

    @Test
    fun resultIsMonotonicInPlatformInset() {
        var previous = -1f
        for (inset in intArrayOf(0, 16, 32, 48, 64, 96, 128, 256)) {
            val zone = edgeDeadZonePx(base, inset.toFloat(), ceiling)
            assertEquals(true, zone >= previous)
            assertEquals(true, zone in base..ceiling)
            previous = zone
        }
    }
}
