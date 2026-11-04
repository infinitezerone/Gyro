package dev.gyro.feature.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import dev.gyro.engine.model.ResizeMode

/** Action for toggling play/pause inside system Picture-in-Picture window */
const val PIP_ACTION_TOGGLE = "dev.gyro.player.action.PIP_TOGGLE"
private const val PIP_TOGGLE_REQUEST_CODE = 1001

/**
 * Upper bound for a manual PiP request to reach the mode callback.
 *
 * The transition itself takes ~300ms; past this the request is treated as dropped, so a hint that
 * nobody clears cannot leave the player without controls.
 */
const val PIP_ENTER_TIMEOUT_MS = 1_500L

/**
 * Builds the interactive Play/Pause action for the system PiP window.
 */
fun buildPipToggleAction(context: Context, isPlaying: Boolean): RemoteAction {
    val iconRes = if (isPlaying) {
        android.R.drawable.ic_media_pause
    } else {
        android.R.drawable.ic_media_play
    }
    val label = if (isPlaying) "Pause" else "Play"
    val intent = Intent(PIP_ACTION_TOGGLE)
        .setPackage(context.packageName)
        .setAction(PIP_ACTION_TOGGLE)
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
    val pending = PendingIntent.getBroadcast(
        context,
        PIP_TOGGLE_REQUEST_CODE,
        intent,
        flags
    )
    return RemoteAction(Icon.createWithResource(context, iconRes), label, label, pending)
}

/**
 * Calculates physical rect of actual video content within the container,
 * stripping letterbox / pillarbox black bars in FIT mode so that Android 12+
 * PiP transition morphs smoothly without border jump artifacts.
 */
fun calculateVideoSourceRect(
    containerBounds: Rect,
    aspectRatio: Rational,
    resizeMode: ResizeMode
): Rect {
    if (resizeMode != ResizeMode.FIT) return containerBounds
    val containerWidth = containerBounds.width().toFloat()
    val containerHeight = containerBounds.height().toFloat()
    if (containerWidth <= 0 || containerHeight <= 0) return containerBounds

    val videoRatio = aspectRatio.numerator.toFloat() / aspectRatio.denominator.toFloat().coerceAtLeast(1f)
    val containerRatio = containerWidth / containerHeight

    return if (containerRatio > videoRatio) {
        // Container wider than video (pillarbox): strip left and right black bars
        val actualWidth = containerHeight * videoRatio
        val horizontalPadding = (containerWidth - actualWidth) / 2f
        Rect(
            (containerBounds.left + horizontalPadding).toInt(),
            containerBounds.top,
            (containerBounds.right - horizontalPadding).toInt(),
            containerBounds.bottom
        )
    } else {
        // Container taller than video (letterbox): strip top and bottom black bars
        val actualHeight = containerWidth / videoRatio
        val verticalPadding = (containerHeight - actualHeight) / 2f
        Rect(
            containerBounds.left,
            (containerBounds.top + verticalPadding).toInt(),
            containerBounds.right,
            (containerBounds.bottom - verticalPadding).toInt()
        )
    }
}

/**
 * Builds PictureInPictureParams with Android 12+ auto-enter and seamless resizing.
 */
fun buildPipParams(
    context: Context,
    isPlaying: Boolean,
    aspectRatio: Rational,
    sourceRect: Rect?,
    autoEnter: Boolean
): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(aspectRatio)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(true)
    }

    sourceRect?.let {
        builder.setSourceRectHint(it)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        builder.setActions(listOf(buildPipToggleAction(context, isPlaying)))
    }

    return builder.build()
}

/** Safely extracts ComponentActivity from context hierarchy. */
tailrec fun Context.findComponentActivity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }

/**
 * Reactively listens to Activity Picture-in-Picture mode changes.
 */
@Composable
fun rememberIsInPipMode(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() } ?: return false

    var isInPipMode by remember(activity) {
        mutableStateOf(activity.isInPictureInPictureMode)
    }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(listener)
        }
    }

    return isInPipMode
}

/**
 * Detects whether the current window geometry represents a floating PiP window.
 * Compares current container bounds against maximumWindowMetrics. PiP windows
 * are strictly smaller than maximum screen metrics in both width and height.
 */
@Composable
fun rememberIsPipWindow(activity: ComponentActivity?): Boolean {
    if (activity == null) return false
    val containerSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val maxBounds = remember(activity, configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.windowManager.maximumWindowMetrics.bounds
        } else {
            null
        }
    } ?: return false

    if (containerSize.width <= 0 || containerSize.height <= 0) return false
    return containerSize.width < maxBounds.width() && containerSize.height < maxBounds.height()
}

/**
 * True from the moment the system notifies this activity that the user is
 * leaving it (home gesture, recents, or a manual PiP entry) - which happens
 * before the PiP transition animation starts. The PiP mode callback only
 * arrives mid-animation, so anything that must disappear before the window
 * shrinks is keyed on this signal instead.
 */
@Composable
fun rememberUserLeavingHint(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() } ?: return false

    var leaving by remember(activity) { mutableStateOf(false) }

    DisposableEffect(activity) {
        val hintListener = Runnable { leaving = true }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Coming back to the front (from PiP or from another app) clears the flag.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) leaving = false
        }
        activity.addOnUserLeaveHintListener(hintListener)
        activity.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            activity.removeOnUserLeaveHintListener(hintListener)
            activity.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    return leaving
}

/**
 * Registers BroadcastReceiver for PIP_ACTION_TOGGLE to handle play/pause clicks.
 */
@Composable
fun PipBroadcastReceiver(
    onTogglePlayPause: () -> Unit
) {
    val context = LocalContext.current
    val currentOnToggle by rememberUpdatedState(onTogglePlayPause)

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == PIP_ACTION_TOGGLE) {
                    currentOnToggle()
                }
            }
        }
        val filter = IntentFilter(PIP_ACTION_TOGGLE)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}
