package dev.gyro.feature.player

import android.app.Activity
import android.os.SystemClock
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.component.sheet.SheetNavState
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.gesture.rememberPlayerZoomState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

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
    val scrubState: PlayerScrubState,
    val zoomState: PlayerZoomState,
    private val activity: Activity?,
) {
    var showControls by mutableStateOf(true)

    var isLocked by mutableStateOf(false)

    var activeSheetTab by mutableStateOf<PlayerSheetRoute?>(null)

    /**
     * 当前打开的抽屉导航状态，由 [PlayerSheetsHost] 挂载时写入、卸载时清空。
     * 外部需要“抽屉当前在哪一页”时读 [SheetNavState.current]，不要再另存一份。
     */
    var sheetNav by mutableStateOf<SheetNavState?>(null)
        internal set

    /**
     * Draw-phase provider of the active portrait bottom sheet's visible height in pixels.
     * Read inside `graphicsLayer` so video surface fit tracks sheet open/close springs and drags
     * in lockstep without recomposing [PlayerVideoFeedLayer].
     */
    internal var sheetVisibleHeightProvider by mutableStateOf<() -> Float>({ 0f })
        private set

    internal fun bindSheetVisibleHeight(provider: () -> Float) {
        sheetVisibleHeightProvider = provider
    }

    internal fun unbindSheetVisibleHeight(provider: () -> Float) {
        if (sheetVisibleHeightProvider === provider) {
            sheetVisibleHeightProvider = { 0f }
        }
    }

    internal fun currentSheetVisibleHeightPx(): Float = sheetVisibleHeightProvider()

    /** Persistent stats readout, toggled from the STATS tab; survives a track change by design. */
    var statsOverlayEnabled by mutableStateOf(false)

    /** Predictive-back drag progress, 0..1. Rendered through a deferred lambda in graphicsLayer. */
    var backProgress by mutableFloatStateOf(0f)

    var gestureHudState by mutableStateOf<GestureHudState>(GestureHudState.Idle)

    var lastInteractionTrigger by mutableLongStateOf(0L)
        private set

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
     * anything: controls must be visible, no scrub in flight, not in PiP, and no sheet covering
     * the screen. Also runs while locked so the unlock button auto-hides after the idle window.
     */
    fun canAutoHide(
        isScrubbing: Boolean,
        isPipLayout: Boolean,
        isPreciseSeeking: Boolean = false
    ): Boolean =
        showControls && !isScrubbing && !isPreciseSeeking && !isPipLayout && activeSheetTab == null

    /** Sleeps out the idle window, then hides the controls. Call only when [canAutoHide] holds. */
    suspend fun autoHideAfter(delayMs: Long = AUTO_HIDE_DELAY_MS) {
        delay(delayMs)
        showControls = false
    }

    fun applySystemBars(isPipLayout: Boolean = false) {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!isPipLayout) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun restoreSystemBars() {
        val window = activity?.window ?: return
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private companion object {
        const val AUTO_HIDE_DELAY_MS = 3_500L
    }
}

@Composable
fun rememberPlayerState(
    activity: Activity?,
    scrubState: PlayerScrubState = remember { PlayerScrubState() },
    zoomState: PlayerZoomState = rememberPlayerZoomState(),
): PlayerState {
    return remember(activity, scrubState, zoomState) {
        PlayerState(
            scrubState = scrubState,
            zoomState = zoomState,
            activity = activity,
        )
    }
}
