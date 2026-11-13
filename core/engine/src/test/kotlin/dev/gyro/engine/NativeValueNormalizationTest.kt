package dev.gyro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the sentinel→nullable translation that every `GyroNative.getXxx` call site relies on.
 *
 * These are the only paths `FakeMpvNativeBridge` cannot cover: the fake implements the bridge
 * interface directly and never runs this translation, so a mistake here stays invisible to the
 * whole existing suite.
 */
class NativeValueNormalizationTest {

    @Test
    fun booleanDistinguishesFalseTrueAndUnknown() {
        assertEquals(false, normalizeBoolean(0))
        assertEquals(true, normalizeBoolean(1))
    }

    @Test
    fun booleanReportsFailureAsUnknownInsteadOfTrue() {
        // 2 is what the Rust side returns for MPV_FORMAT_FLAG read failures.
        assertNull(normalizeBoolean(2))
        // Any unexpected value must degrade to "unknown" rather than falling through to true.
        assertNull(normalizeBoolean(3))
        assertNull(normalizeBoolean((-1).toByte()))
    }

    @Test
    fun longOnlyTreatsMinValueAsFailure() {
        assertEquals(0L, normalizeLong(0L))
        assertEquals(-1L, normalizeLong(-1L))
        assertNull(normalizeLong(Long.MIN_VALUE))
    }

    @Test
    fun doubleOnlyTreatsNanAsFailure() {
        assertEquals(0.0, normalizeDouble(0.0)!!, 0.0)
        assertEquals(-1.5, normalizeDouble(-1.5)!!, 0.0)
        assertNull(normalizeDouble(Double.NaN))
    }
}
