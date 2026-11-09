package dev.gyro.feature.player.gesture

import dev.gyro.feature.player.PlayerScrubState
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt

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
    fun onHudStateChange(state: GestureHudState) {}

    fun getCurrentPositionMs(): Long = 0L
    fun getDurationMs(): Long = 0L
    fun getZoomScale(): Float = 1.0f
    fun getVideoBounds(): androidx.compose.ui.geometry.Rect? = null
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
    private val onSoftwareVolumeChange: ((Double) -> Unit)? = null,
    private val getSoftwareVolume: (() -> Double)? = null,
    private val onSeekTo: ((Long) -> Unit)? = null,
    private val onPlayPrevious: (() -> Unit)? = null,
    private val onPlayNext: (() -> Unit)? = null,
    private val getVideoBounds: (() -> androidx.compose.ui.geometry.Rect?)? = null
) : PlayerGestureCallbacks {

    override fun getVideoBounds(): androidx.compose.ui.geometry.Rect? = getVideoBounds?.invoke()

    private val activity: Activity? = context?.findActivity()
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private var dragStartOverallVolume: Int = 0
    private var dragStartBrightness: Float = 0.5f

    /**
     * 手势触觉反馈（双击 seek / 长按倍速触发 / seek 落点），
     * 统一走 decorView 的轻震动，不依赖额外权限。
     */
    private fun performHaptic() {
        activity?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun getCurrentPositionMs(): Long = currentPosMs()
    override fun getDurationMs(): Long = durationMs()
    override fun getZoomScale(): Float = zoomScale()

    override fun onSingleTap() = onSingleTap.invoke()
    override fun onDoubleTapSeek(deltaMs: Long) {
        performHaptic()
        onDoubleTapSeek.invoke(deltaMs)
    }
    override fun onDoubleTapPlayPause() {
        performHaptic()
        onDoubleTapPlayPause.invoke()
    }
    override fun onFastForwardStart(speed: Float) {
        performHaptic()
        onFastForwardStart.invoke(speed)
    }
    override fun onFastForwardEnd() {
        performHaptic()
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
        val curSoftGain = getSoftwareVolume?.invoke() ?: 100.0
        dragStartOverallVolume = TwoStageVolumeCalculator.computeInitialPercent(
            currentSystemVolume = curSysVol,
            maxSystemVolume = maxVolume,
            currentSoftwareVolume = curSoftGain
        )
        val initialResult = TwoStageVolumeCalculator.calculate(
            dragStartPercent = dragStartOverallVolume,
            deltaRatio = 0f,
            maxSystemVolume = maxVolume
        )
        onHudStateChange.invoke(
            GestureHudState.Volume(
                percent = initialResult.totalPercent,
                volumeRatio = initialResult.volumeRatio,
                isOverdrive = initialResult.isOverdrive
            )
        )
    }

    override fun onVolumeUpdate(deltaRatio: Float) {
        val result = TwoStageVolumeCalculator.calculate(
            dragStartPercent = dragStartOverallVolume,
            deltaRatio = deltaRatio,
            maxSystemVolume = maxVolume
        )
        audioManager?.let { am ->
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (result.systemVolume != curVol) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, result.systemVolume, 0)
            }
        }
        onSoftwareVolumeChange?.invoke(result.softwareVolume)
        onHudStateChange.invoke(
            GestureHudState.Volume(
                percent = result.totalPercent,
                volumeRatio = result.volumeRatio,
                isOverdrive = result.isOverdrive
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
}
