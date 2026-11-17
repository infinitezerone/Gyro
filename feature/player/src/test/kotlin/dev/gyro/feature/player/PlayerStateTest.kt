package dev.gyro.feature.player

import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.gesture.PlayerZoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the timed behaviour that used to live inline in `PlayerScreen`: when the controls may
 * auto-hide, and how long the HDR badge stays up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStateTest {

    private fun state(scope: CoroutineScope) = PlayerState(
        scope = scope,
        scrubState = PlayerScrubState(),
        zoomState = PlayerZoomState(),
        activity = null,
    )

    @Test
    fun autoHideRunsWithVisibleControlsEvenWhenPaused() {
        val subject = state(CoroutineScope(StandardTestDispatcher()))

        assertTrue(subject.canAutoHide(isScrubbing = false, isPipLayout = false))

        subject.hideControls()
        assertFalse(
            "hidden controls have nothing left to hide",
            subject.canAutoHide(isScrubbing = false, isPipLayout = false),
        )
        subject.showControls()

        assertFalse("scrubbing", subject.canAutoHide(isScrubbing = true, isPipLayout = false))
        assertFalse("precise seeking", subject.canAutoHide(isScrubbing = false, isPipLayout = false, isPreciseSeeking = true))
        assertFalse("PiP", subject.canAutoHide(isScrubbing = false, isPipLayout = true))

        subject.isLocked = true
        assertFalse("locked", subject.canAutoHide(isScrubbing = false, isPipLayout = false))
        subject.isLocked = false

        subject.activeSheetTab = PlayerSheetTab.STATS
        assertFalse("sheet open", subject.canAutoHide(isScrubbing = false, isPipLayout = false))
    }

    @Test
    fun autoHideClosesTheControlsAfterTheIdleWindow() = runTest {
        val subject = state(this)

        val job = launch { subject.autoHideAfter() }
        runCurrent()
        assertTrue("still visible before the window elapses", subject.showControls)

        advanceTimeBy(3_600L)
        runCurrent()
        job.join()

        assertFalse(subject.showControls)
    }

    @Test
    fun badgeWindowRestartsInsteadOfStacking() = runTest {
        val subject = state(this)

        subject.revealFormatBadge()
        runCurrent()
        assertTrue(subject.formatBadgeVisible)

        advanceTimeBy(2_000L)
        runCurrent()
        // The next file arrives before the first window ends.
        subject.revealFormatBadge()
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue("the second window must not be cut short by the first", subject.formatBadgeVisible)

        advanceTimeBy(1_400L)
        runCurrent()
        assertFalse(subject.formatBadgeVisible)
    }

    @Test
    fun hideBadgeCancelsThePendingWindow() = runTest {
        val subject = state(this)

        subject.revealFormatBadge()
        runCurrent()
        subject.hideFormatBadge()
        runCurrent()

        assertFalse(subject.formatBadgeVisible)
    }

    @Test
    fun scrubStateTracksPreciseSeekingLifecycle() {
        val scrub = PlayerScrubState()
        assertFalse(scrub.isActive)
        assertFalse(scrub.isPreciseSeeking)

        scrub.begin(0.35f)
        assertTrue(scrub.isActive)
        org.junit.Assert.assertEquals(0.35f, scrub.fraction, 0.0001f)
        assertFalse(scrub.isPreciseSeeking)

        scrub.enterPreciseSeeking(originMs = 35_000L, wasPlaying = true)
        assertTrue(scrub.isPreciseSeeking)
        org.junit.Assert.assertEquals(35_000L, scrub.originTimeMs)
        assertTrue(scrub.wasPlayingBeforePreciseSeek)

        scrub.update(0.42f)
        org.junit.Assert.assertEquals(0.42f, scrub.fraction, 0.0001f)
        assertTrue(scrub.isPreciseSeeking)

        // On finger release, active becomes false but isPreciseSeeking remains TRUE (holding open!)
        scrub.end(0.42f)
        assertFalse(scrub.isActive)
        assertTrue("Precise seeking panel must remain held open on finger lift", scrub.isPreciseSeeking)

        scrub.exitPreciseSeeking()
        assertFalse(scrub.isPreciseSeeking)
    }
}
