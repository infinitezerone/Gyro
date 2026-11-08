package dev.gyro.feature.player

import android.app.Activity
import android.os.SystemClock
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.feature.player.gesture.PlayerZoomState
import dev.gyro.feature.player.gesture.rememberPlayerZoomState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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

    var activeSheetTab by mutableStateOf<PlayerSheetTab?>(null)

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
