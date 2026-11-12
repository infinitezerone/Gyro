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
    fun autoHideOnlyWhilePlayingWithVisibleControls() {
        val subject = state(CoroutineScope(StandardTestDispatcher()))

        assertTrue(subject.canAutoHide(isPlaying = true, isScrubbing = false, isPipLayout = false))

        subject.hideControls()
        assertFalse(
            "hidden controls have nothing left to hide",
            subject.canAutoHide(isPlaying = true, isScrubbing = false, isPipLayout = false),
        )
        subject.showControls()

        assertFalse("paused", subject.canAutoHide(isPlaying = false, isScrubbing = false, isPipLayout = false))
        assertFalse("scrubbing", subject.canAutoHide(isPlaying = true, isScrubbing = true, isPipLayout = false))
        assertFalse("PiP", subject.canAutoHide(isPlaying = true, isScrubbing = false, isPipLayout = true))

        subject.isLocked = true
        assertFalse("locked", subject.canAutoHide(isPlaying = true, isScrubbing = false, isPipLayout = false))
        subject.isLocked = false

        subject.activeSheetTab = PlayerSheetTab.STATS
        assertFalse("sheet open", subject.canAutoHide(isPlaying = true, isScrubbing = false, isPipLayout = false))
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
}
