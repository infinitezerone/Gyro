package dev.gyro.feature.player

import android.app.Activity
import android.os.SystemClock
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.gesture.rememberPlayerZoomState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plain State Holder for UI interaction, system bars, and overlay visibility.
 *
 * Hoists UI-level timed and layout effects out of [PlayerScreen] composable:
 * - Controls visibility with 3.5s auto-hide timer
 * - System bars immersive mode (transient swipe)
 * - HUD and bottom-sheet tab state
 * - Lock state
 */
@Stable
class PlayerState(
    private val scope: CoroutineScope,
    val scrubState: PlayerScrubState,
    val zoomState: PlayerZoomState,
    private val activity: Activity?,
) {
    var showControls by mutableStateOf(true)

    var isLocked by mutableStateOf(false)

    var activeSheetTab by mutableStateOf<PlayerSheetRoute?>(null)

    /**
     * Measured height of the portrait bottom sheet, in pixels. The video surface is fitted into the
     * space above it, so the shift has to follow the real height: sheet content varies per tab and
     * a fixed offset either leaves the picture half-covered or pushes it too far.
     *
     * Zero while no sheet is showing, which is also the state the fit collapses to.
     */
    var sheetHeightPx by mutableIntStateOf(0)
        private set

    /** Reported by the sheet once it is laid out; the surface fit reads it back. */
    fun onSheetHeightMeasured(heightPx: Int) {
        sheetHeightPx = heightPx
    }

    /**
     * How far the portrait sheet has been dragged down, in pixels. A drag shrinks the sheet's
     * footprint without dismissing it, so the video has to read this to stay glued to the sheet's
     * real edge instead of sitting at its fully-open position until the dismiss lands.
     */
    var sheetDragOffsetPx by mutableFloatStateOf(0f)
        private set

    /** Reported on every drag frame; reset to 0 on drag end and cancel. */
    fun onSheetDragged(offsetPx: Float) {
        sheetDragOffsetPx = offsetPx
    }

    /** Persistent stats readout, toggled from the STATS tab; survives a track change by design. */
    var statsOverlayEnabled by mutableStateOf(false)

    /** HDR / Dolby Vision entrance badge. Owned here so the 3.2s window is one piece of logic. */
    var formatBadgeVisible by mutableStateOf(false)
        private set

    /** Predictive-back drag progress, 0..1. Rendered through a deferred lambda in graphicsLayer. */
    var backProgress by mutableFloatStateOf(0f)

    var gestureHudState by mutableStateOf<GestureHudState>(GestureHudState.Idle)

    var lastInteractionTrigger by mutableLongStateOf(0L)
        private set

    private var formatBadgeJob: Job? = null

    fun toggleControls() {
        if (showControls) {
            hideControls()
        } else {
            showControls()
        }
    }

    /**
     * 仅置位显隐标志。3.5s 自动隐藏计时统一由 PlayerScreen 的 LaunchedEffect 驱动
     * （唯一权威计时器：覆盖交互重置、暂停保持、锁定/PiP/sheet 守卫），此处不再另起协程。
     */
    fun showControls() {
        showControls = true
    }

    fun hideControls() {
        showControls = false
    }

    fun resetControlsTimeout() {
        lastInteractionTrigger = SystemClock.uptimeMillis()
    }

    /**
     * Whether the 3.5s auto-hide timer may run.
     *
     * Pulled out of the screen's `LaunchedEffect` so the rule can be asserted without composing
     * anything: controls must be visible, no scrub in flight, not locked,
     * not in PiP, and no sheet covering the screen. Runs on both playing and paused states (Plan A).
     */
    fun canAutoHide(
        isScrubbing: Boolean,
        isPipLayout: Boolean,
        isPreciseSeeking: Boolean = false
    ): Boolean =
        showControls && !isScrubbing && !isPreciseSeeking && !isLocked && !isPipLayout && activeSheetTab == null

    /** Sleeps out the idle window, then hides the controls. Call only when [canAutoHide] holds. */
    suspend fun autoHideAfter(delayMs: Long = AUTO_HIDE_DELAY_MS) {
        delay(delayMs)
        showControls = false
    }

    /**
     * Shows the HDR/Dolby badge for [AUTO_HIDE_DELAY_MS] of badge time, cancelling any previous
     * window so a fast track change does not leave the badge up for the sum of both timers.
     */
    fun revealFormatBadge() {
        formatBadgeJob?.cancel()
        formatBadgeJob = scope.launch {
            formatBadgeVisible = true
            delay(BADGE_VISIBLE_MS)
            formatBadgeVisible = false
        }
    }

    fun hideFormatBadge() {
        formatBadgeJob?.cancel()
        formatBadgeJob = null
        formatBadgeVisible = false
    }

    fun applySystemBars(isPipLayout: Boolean, isLandscape: Boolean) {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            if (showControls && !isPipLayout) {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun restoreSystemBars() {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 3_500L
        const val BADGE_VISIBLE_MS = 3_200L
    }
}

@Composable
fun rememberPlayerState(
    activity: Activity?,
    scrubState: PlayerScrubState = remember { PlayerScrubState() },
    zoomState: PlayerZoomState = rememberPlayerZoomState(),
    scope: CoroutineScope = rememberCoroutineScope(),
): PlayerState {
    return remember(activity, scrubState, zoomState, scope) {
        PlayerState(
            scope = scope,
            scrubState = scrubState,
            zoomState = zoomState,
            activity = activity,
        )
    }
}
