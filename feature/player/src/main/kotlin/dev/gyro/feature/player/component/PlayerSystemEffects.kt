package dev.gyro.feature.player.component

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.PlayerState
import dev.gyro.feature.player.PowerMonitor

/**
 * Handles window, display, and OS-level side effects for [dev.gyro.feature.player.PlayerScreen]:
 * - Predictive back gesture handling
 * - Display P3 Wide Color Gamut switching
 * - Screen-on flag maintenance during playback
 * - Immersive sticky system bars lifecycle
 * - Controls auto-hide timer
 * - HDR format badge reveal/hide triggers
 * - Hardware thermal/power monitor startup
 * - Software audio gain cleanup on exit
 */
@Composable
internal fun PlayerSystemEffects(
    activity: Activity?,
    componentActivity: ComponentActivity?,
    playerState: PlayerState,
    isPlaying: Boolean,
    isPipLayout: Boolean,
    isLandscape: Boolean,
    hdrFormat: String,
    scrubState: PlayerScrubState,
    powerMonitor: PowerMonitor,
    onBack: () -> Unit,
    onUserExit: () -> Unit,
    onResetAudioGain: () -> Unit,
) {
    // 1. Predictive back handler for system back gesture
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backEvent ->
                playerState.backProgress = backEvent.progress
            }
            onUserExit()
            onBack()
        } catch (_: Throwable) {
            playerState.backProgress = 0f
        }
    }

    // 2. Dynamic color space: Enable Display P3 Wide Color Gamut while playing video, restore sRGB on exit
    DisposableEffect(componentActivity) {
        val window = componentActivity?.window ?: return@DisposableEffect onDispose {}
        val previousColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val isWide = componentActivity.windowManager.defaultDisplay?.isWideColorGamut == true
            if (isWide) {
                window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = previousColorMode
            }
        }
    }

    // 3. Keep screen on while playing; release flag on pause or disposal
    DisposableEffect(activity, isPlaying) {
        val window = activity?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 4. Sticky immersive system bars (Transient Swipe)
    LaunchedEffect(playerState.showControls, isPipLayout, isLandscape) {
        playerState.applySystemBars(isPipLayout = isPipLayout, isLandscape = isLandscape)
    }
    DisposableEffect(Unit) {
        onDispose {
            playerState.restoreSystemBars()
        }
    }

    // 5. Controls auto-hide timer (3.5s of inactivity while playing)
    LaunchedEffect(
        playerState.showControls,
        isPlaying,
        scrubState.isActive,
        scrubState.isPreciseSeeking,
        playerState.isLocked,
        isPipLayout,
        playerState.activeSheetTab,
        playerState.lastInteractionTrigger
    ) {
        if (playerState.canAutoHide(
                isScrubbing = scrubState.isActive,
                isPipLayout = isPipLayout,
                isPreciseSeeking = scrubState.isPreciseSeeking
            )
        ) {
            playerState.autoHideAfter()
        }
    }

    // 6. Dolby Vision / HDR format badge trigger
    LaunchedEffect(hdrFormat) {
        if (hdrFormat.isNotBlank()) {
            playerState.revealFormatBadge()
        } else {
            playerState.hideFormatBadge()
        }
    }

    // 7. Power and thermal monitor
    LaunchedEffect(Unit) {
        powerMonitor.startMonitoring(this)
    }

    // 8. Clean up software gain on exit
    DisposableEffect(Unit) {
        onDispose {
            onResetAudioGain()
        }
    }
}
