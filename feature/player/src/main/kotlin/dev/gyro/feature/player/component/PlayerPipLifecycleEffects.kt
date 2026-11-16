package dev.gyro.feature.player.component

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.gyro.feature.player.PipBroadcastReceiver
import dev.gyro.feature.player.PlayerState
import dev.gyro.feature.player.PlayerViewportState
import dev.gyro.feature.player.gesture.PlayerZoomState

/**
 * Manages Picture-in-Picture (PiP) lifecycle side effects:
 * - BroadcastReceiver registration for notification play/pause toggles
 * - Sensor orientation memory across PiP transitions
 * - Screen lock release on PiP entry
 * - Zoom/pan save on PiP entry and restore on full-screen exit
 */
@Composable
internal fun PlayerPipLifecycleEffects(
    activity: Activity?,
    playerState: PlayerState,
    zoomState: PlayerZoomState,
    viewport: PlayerViewportState,
    composeVideoBounds: Rect?,
    isPipLayout: Boolean,
    hideChrome: Boolean,
    isLandscape: Boolean,
    onTogglePlayPause: () -> Unit,
) {
    // Picture-in-Picture (PiP) Broadcast Receiver
    PipBroadcastReceiver(
        onTogglePlayPause = onTogglePlayPause
    )

    var orientationBeforePip by rememberSaveable {
        mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }

    // In normal playback, record the current orientation
    if (!isPipLayout && !hideChrome) {
        orientationBeforePip = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    var wasInPipLayout by remember { mutableStateOf(false) }
    LaunchedEffect(isPipLayout) {
        if (isPipLayout) {
            // Unlock screen controls in PiP mode
            playerState.isLocked = false
        } else if (wasInPipLayout && !isPipLayout) {
            // Restore previous screen orientation when exiting PiP back to full screen
            if (orientationBeforePip != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity?.requestedOrientation = orientationBeforePip
            }
            playerState.showControls = false
        }
        wasInPipLayout = isPipLayout
    }

    // PiP Zoom lifecycle: reset to 1.0x on enter so small floating window shows full video context;
    // restore previous user zoom & pan seamlessly on return to full screen.
    LaunchedEffect(hideChrome, isPipLayout) {
        val inPip = hideChrome || isPipLayout
        if (inPip) {
            if (zoomState.isZoomed) {
                zoomState.saveAndReset(
                    viewportSize = viewport.containerSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                        Size(it.width.toFloat(), it.height.toFloat())
                    },
                    contentBounds = composeVideoBounds
                )
            }
        } else {
            if (zoomState.hasSavedZoom) {
                zoomState.restore(
                    viewportSize = viewport.fullscreenContainerSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                        Size(it.width.toFloat(), it.height.toFloat())
                    } ?: viewport.containerSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                        Size(it.width.toFloat(), it.height.toFloat())
                    },
                    contentBounds = composeVideoBounds
                )
            }
        }
    }
}
