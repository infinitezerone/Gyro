package dev.gyro.feature.player.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHudCollapseTest {

    @Test
    fun initialVolumeAndBrightnessStartExpanded() {
        val vol = LevelHudMorphState().onHudUpdate(LevelHudKind.VOLUME, 0.50f, nowMs = 1_000L)
        assertEquals(LevelHudKind.VOLUME, vol.activeKind)
        assertFalse(vol.isCompact)

        val bri = LevelHudMorphState().onHudUpdate(LevelHudKind.BRIGHTNESS, 0.30f, nowMs = 2_000L)
        assertEquals(LevelHudKind.BRIGHTNESS, bri.activeKind)
        assertFalse(bri.isCompact)
    }

    @Test
    fun quickLargeDragBeforeMinimumElapsedStaysExpanded() {
        var state = LevelHudMorphState().onHudUpdate(LevelHudKind.VOLUME, 0.20f, nowMs = 1_000L)
        state = state.onHudUpdate(
            LevelHudKind.VOLUME,
            0.45f,
            nowMs = 1_000L + LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS - 1L
        )
        assertFalse(state.isCompact)
    }

    @Test
    fun longElapsedWithTinyDeltaStaysExpanded() {
        var state = LevelHudMorphState().onHudUpdate(LevelHudKind.BRIGHTNESS, 0.50f, nowMs = 1_000L)
        val tinyDelta = LEVEL_HUD_COLLAPSE_MIN_DELTA * 0.5f
        state = state.onHudUpdate(
            LevelHudKind.BRIGHTNESS,
            0.50f + tinyDelta,
            nowMs = 1_000L + LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS + 200L
        )
        assertFalse(state.isCompact)
    }

    @Test
    fun continuousAdjustmentCollapsesAndRemainsCompactWhileActive() {
        var state = LevelHudMorphState().onHudUpdate(LevelHudKind.VOLUME, 0.30f, nowMs = 1_000L)
        state = state.onHudUpdate(
            LevelHudKind.VOLUME,
            0.30f + LEVEL_HUD_COLLAPSE_MIN_DELTA,
            nowMs = 1_000L + LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS
        )
        assertTrue(state.isCompact)

        // Subsequent small adjustment stays compact for stability
        state = state.onHudUpdate(LevelHudKind.VOLUME, 0.34f, nowMs = 1_600L)
        assertTrue(state.isCompact)
    }

    @Test
    fun idlePreservesCompactForExitFadeOutThenNextSessionResetsExpanded() {
        var state = LevelHudMorphState().onHudUpdate(LevelHudKind.VOLUME, 0.25f, nowMs = 1_000L)
        state = state.onHudUpdate(
            LevelHudKind.VOLUME,
            0.40f,
            nowMs = 1_000L + LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS
        )
        assertTrue(state.isCompact)

        val exiting = state.onHudUpdate(LevelHudKind.NONE, 0f, nowMs = 2_000L)
        assertEquals(LevelHudKind.NONE, exiting.activeKind)
        assertTrue(exiting.isCompact)

        val nextSession = exiting.onHudUpdate(LevelHudKind.VOLUME, 0.40f, nowMs = 3_000L)
        assertEquals(LevelHudKind.VOLUME, nextSession.activeKind)
        assertFalse(nextSession.isCompact)
    }

    @Test
    fun switchingDirectlyBetweenVolumeAndBrightnessResetsExpanded() {
        var state = LevelHudMorphState().onHudUpdate(LevelHudKind.VOLUME, 0.25f, nowMs = 1_000L)
        state = state.onHudUpdate(
            LevelHudKind.VOLUME,
            0.40f,
            nowMs = 1_000L + LEVEL_HUD_COLLAPSE_MIN_ELAPSED_MS
        )
        assertTrue(state.isCompact)

        val switched = state.onHudUpdate(LevelHudKind.BRIGHTNESS, 0.60f, nowMs = 1_500L)
        assertEquals(LevelHudKind.BRIGHTNESS, switched.activeKind)
        assertFalse(switched.isCompact)
    }
}
