package dev.gyro.feature.player.gesture

import dev.gyro.feature.player.PlayerScrubState
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.Offset

/**
 * Callbacks for player gesture events and dynamic state queries.
 * Decouples the gesture detector state machine from system hardware services
 * and player presentation logic.
 */
interface PlayerGestureCallbacks {
    fun onSingleTap() {}
    fun onDoubleTapSeek(deltaMs: Long) {}
    fun onDoubleTapPlayPause() {}
    fun onFastForwardStart(speed: Float) {}
    fun onFastForwardEnd() {}
    fun onZoomPan(zoomChange: Float, panDelta: Offset, centroid: Offset, isPinch: Boolean) {}
    fun onResetZoom() {}
    fun onSettleZoom() {}
    fun onSeekStart(currentPosMs: Long) {}
    fun onSeekUpdate(targetMs: Long, deltaMs: Long, totalMs: Long) {}
    fun onSeekEnd(finalMs: Long) {}
    fun onBrightnessStart() {}
    fun onBrightnessUpdate(deltaRatio: Float) {}
    fun onVolumeStart() {}
    fun onVolumeUpdate(deltaRatio: Float) {}
    fun onPlayPrevious() {}
    fun onPlayNext() {}
    fun onVerticalSwitchDragStart() {}
    fun onVerticalSwitchDragUpdate(deltaY: Float) {}
    fun onVerticalSwitchDragEnd(deltaY: Float) {}
    fun onHudStateChange(state: GestureHudState) {}

    fun getCurrentPositionMs(): Long = 0L
    fun getDurationMs(): Long = 0L
    fun getZoomScale(): Float = 1.0f
    fun getVideoBounds(): androidx.compose.ui.geometry.Rect? = null
    fun isPreciseSeeking(): Boolean = false
    fun isPlaying(): Boolean = true
    fun areControlsVisible(): Boolean = false
    fun getControlsGestureGuardMetrics(): ControlsGestureGuardMetrics = ControlsGestureGuardMetrics.None
}

/**
 * Default implementation of [PlayerGestureCallbacks] bridging Android hardware
 * services (screen brightness via Activity window attributes, audio stream volume via AudioManager)
 * and player interactive state (PlayerScrubState, zoom callbacks, playback engine).
 */
class DefaultPlayerGestureCallbacks(
    private val context: Context?,
    private val currentPosMs: () -> Long,
    private val durationMs: () -> Long,
    private val scrubState: PlayerScrubState?,
    private val zoomScale: () -> Float,
    private val onZoomChange: (zoom: Float, pan: Offset, centroid: Offset, isPinch: Boolean) -> Unit,
    private val onResetZoom: () -> Unit,
    private val onSettleZoom: () -> Unit,
    private val onSingleTap: () -> Unit,
    private val onDoubleTapSeek: (Long) -> Unit,
    private val onDoubleTapPlayPause: () -> Unit,
    private val onFastForwardStart: (Float) -> Unit,
    private val onFastForwardEnd: () -> Unit,
    private val onHudStateChange: (GestureHudState) -> Unit,
    private val onSeekTo: ((Long) -> Unit)? = null,
    private val onPlayPrevious: (() -> Unit)? = null,
    private val onPlayNext: (() -> Unit)? = null,
    private val getVideoBounds: (() -> androidx.compose.ui.geometry.Rect?)? = null,
    private val onVerticalSwitchDragStart: (() -> Unit)? = null,
    private val onVerticalSwitchDragUpdate: ((Float) -> Unit)? = null,
    private val onVerticalSwitchDragEnd: ((Float) -> Unit)? = null,
    private val isPlaying: () -> Boolean = { true },
    private val areControlsVisible: () -> Boolean = { false },
    private val controlsGestureGuardMetrics: () -> ControlsGestureGuardMetrics = { ControlsGestureGuardMetrics.None },
) : PlayerGestureCallbacks {

    override fun getVideoBounds(): androidx.compose.ui.geometry.Rect? = getVideoBounds?.invoke()

    private val activity: Activity? = context?.findActivity()
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator: Vibrator? = runCatching {
        context?.let { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }.getOrNull()
    private val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private var dragStartOverallVolume: Int = 0
    private var dragStartBrightness: Float = 0.5f

    /**
     * Last system volume actually pushed to AudioManager. A drag produces one callback per pointer
     * event, but the stream only has a handful of integer steps: writing the same value again is
     * pure binder work on the UI thread, which made the volume change feel sticky.
     */
    private var appliedSystemVolume: Int = -1

    /**
     * 手势触觉反馈（切集），
     * 统一走 decorView 的轻震动，不依赖额外权限。
     */
    private fun performHaptic() {
        activity?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * 长按倍速专用极轻震动：在支持振幅控制的线性马达上使用最低可感知单脉冲（8ms / 振幅 15），
     * 绕过部分 ROM 对 KEYBOARD_TAP 的重击映射；不支持振幅控制时降级为系统最轻刻度反馈。
     */
    @SuppressLint("MissingPermission")
    private fun performMinimalLongPressHaptic() {
        val ctx = context ?: return
        val hapticEnabled = runCatching {
            Settings.System.getInt(
                ctx.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
        if (!hapticEnabled) return

        val vib = vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vib?.hasAmplitudeControl() == true) {
            val effect = VibrationEffect.createOneShot(
                LONG_PRESS_HAPTIC_DURATION_MS,
                LONG_PRESS_HAPTIC_MIN_AMPLITUDE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                )
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect)
            }
            return
        }

        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        activity?.window?.decorView?.performHapticFeedback(constant)
    }

    override fun getCurrentPositionMs(): Long = currentPosMs()
    override fun getDurationMs(): Long = durationMs()
    override fun getZoomScale(): Float = zoomScale()
    override fun isPreciseSeeking(): Boolean = scrubState?.isPreciseSeeking == true
    override fun isPlaying(): Boolean = isPlaying.invoke()
    override fun areControlsVisible(): Boolean = areControlsVisible.invoke()
    override fun getControlsGestureGuardMetrics(): ControlsGestureGuardMetrics = controlsGestureGuardMetrics.invoke()

    override fun onSingleTap() = onSingleTap.invoke()
    override fun onDoubleTapSeek(deltaMs: Long) = onDoubleTapSeek.invoke(deltaMs)
    override fun onDoubleTapPlayPause() = onDoubleTapPlayPause.invoke()
    override fun onFastForwardStart(speed: Float) {
        if (!isPlaying()) return
        performMinimalLongPressHaptic()
        onFastForwardStart.invoke(speed)
    }
    override fun onFastForwardEnd() {
        performMinimalLongPressHaptic()
        onFastForwardEnd.invoke()
    }

    override fun onPlayPrevious() {
        performHaptic()
        onPlayPrevious?.invoke()
    }

    override fun onPlayNext() {
        performHaptic()
        onPlayNext?.invoke()
    }

    override fun onVerticalSwitchDragStart() {
        onVerticalSwitchDragStart?.invoke()
    }

    override fun onVerticalSwitchDragUpdate(deltaY: Float) {
        onVerticalSwitchDragUpdate?.invoke(deltaY)
    }

    override fun onVerticalSwitchDragEnd(deltaY: Float) {
        onVerticalSwitchDragEnd?.invoke(deltaY)
    }

    override fun onZoomPan(zoomChange: Float, panDelta: Offset, centroid: Offset, isPinch: Boolean) {
        onZoomChange.invoke(zoomChange, panDelta, centroid, isPinch)
    }

    override fun onResetZoom() = onResetZoom.invoke()
    override fun onSettleZoom() = onSettleZoom.invoke()

    override fun onSeekStart(currentPosMs: Long) {
        val total = durationMs()
        if (total > 0L) {
            val fraction = (currentPosMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            scrubState?.begin(fraction)
        }
    }

    override fun onSeekUpdate(targetMs: Long, deltaMs: Long, totalMs: Long) {
        if (totalMs > 0L) {
            val fraction = (targetMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
            scrubState?.update(fraction)
            onHudStateChange.invoke(GestureHudState.Seek(targetMs, deltaMs, totalMs))
        }
    }

    override fun onSeekEnd(finalMs: Long) {
        val currentMs = currentPosMs()
        scrubState?.let {
            it.end(it.fraction)
            if (onSeekTo != null) {
                onSeekTo.invoke(finalMs)
            } else {
                onDoubleTapSeek.invoke(finalMs - currentMs)
            }
        }
    }

    override fun onBrightnessStart() {
        activity?.let { act ->
            val lp = act.window.attributes
            var cur = lp.screenBrightness
            if (cur < 0) cur = 0.5f
            dragStartBrightness = cur
            val pct = (cur * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Brightness(pct, cur))
        }
    }

    override fun onBrightnessUpdate(deltaRatio: Float) {
        activity?.let { act ->
            val lp = act.window.attributes
            val newBrightness = (dragStartBrightness + deltaRatio).coerceIn(0.01f, 1.0f)
            lp.screenBrightness = newBrightness
            act.window.attributes = lp
            val pct = (newBrightness * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Brightness(pct, newBrightness))
        }
    }

    override fun onVolumeStart() {
        val curSysVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        appliedSystemVolume = curSysVol
        dragStartOverallVolume = VolumeGestureCalculator.computeInitialPercent(
            currentSystemVolume = curSysVol,
            maxSystemVolume = maxVolume
        )
        val initialResult = VolumeGestureCalculator.calculate(
            dragStartPercent = dragStartOverallVolume,
            deltaRatio = 0f,
            maxSystemVolume = maxVolume
        )
        onHudStateChange.invoke(
            GestureHudState.Volume(
                percent = initialResult.totalPercent,
                volumeRatio = initialResult.volumeRatio
            )
        )
    }

    override fun onVolumeUpdate(deltaRatio: Float) {
        val result = VolumeGestureCalculator.calculate(
            dragStartPercent = dragStartOverallVolume,
            deltaRatio = deltaRatio,
            maxSystemVolume = maxVolume
        )
        if (result.systemVolume != appliedSystemVolume) {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, result.systemVolume, 0)
            appliedSystemVolume = result.systemVolume
        }
        onHudStateChange.invoke(
            GestureHudState.Volume(
                percent = result.totalPercent,
                volumeRatio = result.volumeRatio
            )
        )
    }

    override fun onHudStateChange(state: GestureHudState) {
        onHudStateChange.invoke(state)
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    internal companion object {
        const val LONG_PRESS_HAPTIC_DURATION_MS = 8L
        const val LONG_PRESS_HAPTIC_MIN_AMPLITUDE = 15
    }
}
