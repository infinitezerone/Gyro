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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.app.PictureInPictureUiStateCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.engine.model.ResizeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Delay following PiP exit to allow the window resizing animation to settle before
 * resuming standard buffer allocation.
 */
const val PIP_EXIT_SETTLE_MS = 300L

/**
 * Builds the interactive Play/Pause action for the system PiP window.
 *
 * The label surfaces in the system window and TalkBack, so it must follow app locale, not a
 * hardcoded string.
 */
fun buildPipToggleAction(context: Context, isPlaying: Boolean): RemoteAction {
    val iconRes = if (isPlaying) {
        GyroIcons.Res.Pause
    } else {
        GyroIcons.Res.Play
    }
    val label = context.getString(
        if (isPlaying) {
            dev.gyro.core.designsystem.R.string.cd_pause
        } else {
            dev.gyro.core.designsystem.R.string.cd_play
        }
    )
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
 * stripping letterbox / pillarbox black bars in FIT / 16:9 / 21:9 modes so that Android 12+
 * PiP transition morphs smoothly without border jump artifacts.
 */
fun calculateVideoSourceRect(
    containerBounds: Rect,
    aspectRatio: Rational,
    resizeMode: ResizeMode
): Rect {
    val videoRatio = when (resizeMode) {
        ResizeMode.FIT -> aspectRatio.numerator.toFloat() / aspectRatio.denominator.toFloat().coerceAtLeast(1f)
        ResizeMode.ASPECT_16_9 -> 16f / 9f
        ResizeMode.ASPECT_4_3 -> 4f / 3f
        ResizeMode.CROP, ResizeMode.STRETCH -> return containerBounds
    }
    return calculateVideoSourceRect(
        left = containerBounds.left,
        top = containerBounds.top,
        right = containerBounds.right,
        bottom = containerBounds.bottom,
        videoRatio = videoRatio
    )
}

/**
 * Pure strip math over primitives so it stays JVM-testable: android.graphics.Rect methods are
 * stubbed no-ops in unit tests, so the real geometry must never be read from a Rect instance.
 * The returned rect is built via field assignment for the same reason.
 */
internal fun calculateVideoSourceRect(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    videoRatio: Float
): Rect {
    val containerWidth = (right - left).toFloat()
    val containerHeight = (bottom - top).toFloat()
    if (containerWidth <= 0f || containerHeight <= 0f || videoRatio <= 0f) {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    val containerRatio = containerWidth / containerHeight

    return if (containerRatio > videoRatio) {
        // Container wider than video (pillarbox): strip left and right black bars
        val actualWidth = containerHeight * videoRatio
        val horizontalPadding = (containerWidth - actualWidth) / 2f
        Rect().apply {
            this.left = (left + horizontalPadding).toInt()
            this.top = top
            this.right = (right - horizontalPadding).toInt()
            this.bottom = bottom
        }
    } else {
        // Container taller than video (letterbox): strip top and bottom black bars
        val actualHeight = containerWidth / videoRatio
        val verticalPadding = (containerHeight - actualHeight) / 2f
        Rect().apply {
            this.left = left
            this.top = (top + verticalPadding).toInt()
            this.right = right
            this.bottom = (bottom - verticalPadding).toInt()
        }
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
        // Seamless resize stretches the last frame during window resizes instead of waiting for
        // a redraw. This is the documented recommendation for video content - and on ColorOS,
        // where the PiP window is freely resizable by dragging its edges, every drag hits this
        // path. Distortion of one frame beats a redraw gap; never "tune" this to false for a
        // player without a device-verified reason.
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
 * Reactively listens to Activity Picture-in-Picture UI transition state.
 * Fires at the immediate start of swipe-to-home gestures (before the window shrinks)
 * allowing controls to disappear before the system captures the initial snapshot.
 */
@Composable
fun rememberIsTransitioningToPip(
    activity: ComponentActivity?,
    isInPipMode: Boolean,
    onControlsHide: () -> Unit
): Boolean {
    if (activity == null) return false
    var isTransitioning by remember(activity) { mutableStateOf(false) }

    LaunchedEffect(isInPipMode) {
        if (!isInPipMode) {
            isTransitioning = false
        }
    }

    DisposableEffect(activity) {
        var wasTransitioning = false
        val listener = Consumer<PictureInPictureUiStateCompat> { state ->
            if (state.isTransitioningToPip) {
                wasTransitioning = true
                isTransitioning = true
                onControlsHide()
            } else if (wasTransitioning && !activity.isInPictureInPictureMode) {
                wasTransitioning = false
                isTransitioning = false
            }
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Coming back to foreground (expanding PiP) always clears transitioning
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isTransitioning = false
                wasTransitioning = false
            }
        }
        activity.addOnPictureInPictureUiStateChangedListener(listener)
        activity.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            activity.removeOnPictureInPictureUiStateChangedListener(listener)
            activity.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    return isTransitioning
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
fun rememberUserLeavingHint(onControlsHide: () -> Unit, enabled: Boolean = true): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() } ?: return false

    var leaving by remember(activity) { mutableStateOf(false) }

    DisposableEffect(activity, enabled) {
        // Without auto-PiP a Home press leads nowhere: nothing shrinks into a floating window, so
        // reacting to it only hid the chrome and collapsed the controls on the way out.
        if (!enabled) return@DisposableEffect onDispose {}
        val hintListener = Runnable {
            leaving = true
            onControlsHide()
        }
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
 * Unified reactive Picture-in-Picture state and controls for the player screen.
 */
@Stable
class PlayerPipState internal constructor(
    val isInPipMode: Boolean,
    val isPipWindow: Boolean,
    val isTransitioning: Boolean,
    val isSettling: Boolean,
    val isLeavingForeground: Boolean,
    val pipRequested: Boolean,
    /**
     * Whether leaving the foreground can start a PiP transition at all. Without it, a plain Home
     * press would hide the chrome on the way out even though nothing is shrinking into PiP.
     */
    val autoPipEnabled: Boolean,
    private val enterPipAction: () -> Unit
) {
    /** True when either geometry or system mode indicates the player is in PiP floating layout. */
    val isPipLayout: Boolean get() = isPipWindow || isInPipMode

    /**
     * True when in PiP layout or when entering PiP (system transition signal, manual request, or
     * the leave hint while auto-PiP can actually fire), so controls and chrome are hidden before
     * the window shrinks.
     */
    val shouldHideChrome: Boolean
        get() = isPipLayout || (isLeavingForeground && autoPipEnabled) ||
            pipRequested || isTransitioning

    /** True while PiP transition or exit settle is active, used to freeze buffer relocking. */
    val isTransitionActive: Boolean get() = isSettling || isInPipMode

    /** Manually triggers PiP entry with frame-synced control concealment. */
    fun enterPip() = enterPipAction()
}

/**
 * Consolidates PiP state observation, Android 12+ dynamic parameters, Android 8-11 hint listener,
 * and frame-synced manual PiP entry into a single structured hook.
 */
@Composable
fun rememberPlayerPipState(
    activity: ComponentActivity?,
    isPlaying: Boolean,
    autoPipEnabled: Boolean,
    targetAspectRatio: Rational,
    videoSourceRect: Rect?,
    onControlsVisibilityChange: (Boolean) -> Unit,
    onPipDismissed: (() -> Unit)? = null
): PlayerPipState {
    val onControlsHide = remember(onControlsVisibilityChange) {
        { onControlsVisibilityChange(false) }
    }
    val isInPipMode = rememberIsInPipMode()
    val isPipWindow = rememberIsPipWindow(activity)
    val isTransitioning = rememberIsTransitioningToPip(activity, isInPipMode, onControlsHide)
    // 系统只在「播放中 + 开启自动画中画」时自动进小窗（autoEnter = isPlaying && autoPipEnabled），
    // 暂停时按 Home 不会进小窗，此时不应收起控制层。
    val isLeavingForeground = rememberUserLeavingHint(onControlsHide, enabled = autoPipEnabled && isPlaying)

    val latestOnPipDismissed by rememberUpdatedState(onPipDismissed)
    val latestIsInPipMode by rememberUpdatedState(isInPipMode)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(activity, lifecycleOwner) {
        val act = activity ?: return@DisposableEffect onDispose {}
        val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
            if (!info.isInPictureInPictureMode && act.isFinishing) {
                latestOnPipDismissed?.invoke()
            }
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val isInteractive = (act.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.isInteractive != false
                if (shouldDismissPipOnStop(
                        isActivityInPipMode = act.isInPictureInPictureMode,
                        isInPipMode = latestIsInPipMode,
                        isInteractive = isInteractive,
                        isChangingConfigurations = act.isChangingConfigurations,
                    )
                ) {
                    latestOnPipDismissed?.invoke()
                }
            }
        }
        act.addOnPictureInPictureModeChangedListener(modeListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            act.removeOnPictureInPictureModeChangedListener(modeListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val isPipLayout = isPipWindow || isInPipMode
    LaunchedEffect(isPipLayout) {
        if (isPipLayout) {
            onControlsVisibilityChange(false)
        }
    }

    var pipRequested by remember { mutableStateOf(false) }

    var isSettling by remember { mutableStateOf(isInPipMode) }

    LaunchedEffect(isInPipMode) {
        pipRequested = false
        if (isInPipMode) {
            isSettling = true
        } else if (isSettling) {
            delay(PIP_EXIT_SETTLE_MS)
            isSettling = false
        }
    }

    LaunchedEffect(pipRequested) {
        if (!pipRequested) return@LaunchedEffect
        delay(PIP_ENTER_TIMEOUT_MS)
        pipRequested = false
    }

    val context = LocalContext.current
    LaunchedEffect(activity, isPlaying, autoPipEnabled, targetAspectRatio, videoSourceRect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            dev.gyro.engine.trace.SwitchTrace.log(
                "PIP_PARAMS_APPLY",
                "aspect=$targetAspectRatio, sourceRect=$videoSourceRect, autoEnter=${isPlaying && autoPipEnabled}"
            )
            val params = buildPipParams(
                context = context,
                isPlaying = isPlaying,
                aspectRatio = targetAspectRatio,
                sourceRect = videoSourceRect,
                autoEnter = isPlaying && autoPipEnabled
            )
            activity.setPictureInPictureParams(params)
        }
    }

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestAutoPipEnabled by rememberUpdatedState(autoPipEnabled)
    val latestAspectRatio by rememberUpdatedState(targetAspectRatio)
    val latestSourceRect by rememberUpdatedState(videoSourceRect)
    DisposableEffect(activity) {
        val act = activity ?: return@DisposableEffect onDispose {}
        val hintListener = Runnable {
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R &&
                latestIsPlaying && latestAutoPipEnabled
            ) {
                val params = buildPipParams(
                    context = act,
                    isPlaying = latestIsPlaying,
                    aspectRatio = latestAspectRatio,
                    sourceRect = latestSourceRect,
                    autoEnter = true
                )
                act.enterPictureInPictureMode(params)
            }
        }
        act.addOnUserLeaveHintListener(hintListener)
        onDispose {
            act.removeOnUserLeaveHintListener(hintListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val disabledParams = PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(false)
                        .build()
                    act.setPictureInPictureParams(disabledParams)
                } catch (e: Exception) {
                    // Ignored if activity is finishing or destroyed
                }
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val latestOnControlsVisibilityChange by rememberUpdatedState(onControlsVisibilityChange)
    val enterPipAction = remember(activity, context) {
        {
            if (!pipRequested && activity != null) {
                pipRequested = true
                latestOnControlsVisibilityChange(false)
                coroutineScope.launch {
                    withFrameNanos {}
                    withFrameNanos {}
                    val sourceRect = latestSourceRect
                    val aspect = latestAspectRatio
                    val playing = latestIsPlaying
                    dev.gyro.engine.trace.SwitchTrace.log(
                        "PIP_ENTER_REQUEST",
                        "aspect=$aspect, sourceRect=$sourceRect"
                    )
                    val params = buildPipParams(
                        context = context,
                        isPlaying = playing,
                        aspectRatio = aspect,
                        sourceRect = sourceRect,
                        autoEnter = true
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // setPictureInPictureParams keeps the just-built params as the ones the
                        // system resolves for this entry; the explicit overload is used so the
                        // boolean result can reset pipRequested on rejection. The enter animation
                        // itself is the ROM's programmatic-entry path and does not crop to the
                        // source rect hint (verified 2026-11-15: params verified correct and
                        // freshly applied, landscape bars still ride along - see
                        // docs/player_state_ownership.md section 4).
                        activity.setPictureInPictureParams(params)
                        val entered = activity.enterPictureInPictureMode(params)
                        if (!entered) {
                            pipRequested = false
                        }
                    }
                }
            }
        }
    }

    return remember(isInPipMode, isPipWindow, isTransitioning, isSettling, isLeavingForeground, pipRequested, autoPipEnabled, enterPipAction) {
        PlayerPipState(
            isInPipMode = isInPipMode,
            isPipWindow = isPipWindow,
            isTransitioning = isTransitioning,
            isSettling = isSettling,
            isLeavingForeground = isLeavingForeground,
            pipRequested = pipRequested,
            autoPipEnabled = autoPipEnabled,
            enterPipAction = enterPipAction
        )
    }
}

/**
 * Returns true when `ON_STOP` during PiP indicates the user dismissed the PiP window rather than
 * locking the screen (`isInteractive == false`) or rotating/recreating the Activity.
 */
internal fun shouldDismissPipOnStop(
    isActivityInPipMode: Boolean,
    isInPipMode: Boolean,
    isInteractive: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = (isActivityInPipMode || isInPipMode) && isInteractive && !isChangingConfigurations

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
