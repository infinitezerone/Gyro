package dev.gyro.feature.player.component

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import dev.gyro.feature.player.PlayerScrubState
import dev.gyro.feature.player.PlayerState

/**
 * Handles window, display, and OS-level side effects for [dev.gyro.feature.player.PlayerScreen]:
 * - Predictive back gesture handling
 * - Authoritative Window Color Mode switching (HDR / Display P3 Wide Color Gamut / sRGB restore)
 * - Screen-on flag maintenance during playback
 * - Immersive sticky system bars lifecycle
 * - Controls auto-hide timer
 * - HDR format badge reveal/hide triggers
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
    isHdr: Boolean,
    hdrFormat: String,
    scrubState: PlayerScrubState,
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

    // 2. Authoritative window color space management (restores original colorMode on exit)
    @SuppressLint("WrongConstant")
    DisposableEffect(componentActivity) {
        val window = componentActivity?.window ?: return@DisposableEffect onDispose {}
        val previousColorMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode
        } else 0
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                window.colorMode = previousColorMode
            }
        }
    }

    LaunchedEffect(componentActivity, isHdr) {
        val act = componentActivity ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                act.display
            } else {
                act.windowManager.defaultDisplay
            }
            val supportsHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.isHdr == true
            } else {
                @Suppress("DEPRECATION")
                display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
            }
            val supportsWideGamut = display?.isWideColorGamut == true
            act.window.colorMode = when {
                isHdr && supportsHdr -> ActivityInfo.COLOR_MODE_HDR
                supportsWideGamut -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                else -> ActivityInfo.COLOR_MODE_DEFAULT
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

    // 7. Clean up software gain on exit
    DisposableEffect(Unit) {
        onDispose {
            onResetAudioGain()
        }
    }

    // 8. Restore default screen orientation when PlayerScreen leaves composition (unless rotating)
    DisposableEffect(activity) {
        onDispose {
            val act = activity ?: return@onDispose
            if (!act.isChangingConfigurations &&
                act.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
