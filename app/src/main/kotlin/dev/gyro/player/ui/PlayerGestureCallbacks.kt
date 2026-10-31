package dev.gyro.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
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
    fun onHudStateChange(state: GestureHudState) {}

    fun getCurrentPositionMs(): Long = 0L
    fun getDurationMs(): Long = 0L
    fun getZoomScale(): Float = 1.0f
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
    private val onFastForwardStart: () -> Unit,
    private val onFastForwardEnd: () -> Unit,
    private val onHudStateChange: (GestureHudState) -> Unit
) : PlayerGestureCallbacks {

    private val activity: Activity? = context?.findActivity()
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private var dragStartVolume: Int = 0
    private var dragStartBrightness: Float = 0.5f

    override fun getCurrentPositionMs(): Long = currentPosMs()
    override fun getDurationMs(): Long = durationMs()
    override fun getZoomScale(): Float = zoomScale()

    override fun onSingleTap() = onSingleTap.invoke()
    override fun onDoubleTapSeek(deltaMs: Long) = onDoubleTapSeek.invoke(deltaMs)
    override fun onDoubleTapPlayPause() = onDoubleTapPlayPause.invoke()
    override fun onFastForwardStart(speed: Float) = onFastForwardStart.invoke()
    override fun onFastForwardEnd() = onFastForwardEnd.invoke()

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
            onDoubleTapSeek.invoke(finalMs - currentMs)
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
        audioManager?.let { am ->
            dragStartVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val ratio = dragStartVolume.toFloat() / maxVolume.toFloat()
            val pct = (ratio * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Volume(pct, ratio))
        }
    }

    override fun onVolumeUpdate(deltaRatio: Float) {
        audioManager?.let { am ->
            val targetVol = (dragStartVolume + deltaRatio * maxVolume).roundToInt().coerceIn(0, maxVolume)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (targetVol != curVol) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
            val ratio = targetVol.toFloat() / maxVolume.toFloat()
            val pct = (ratio * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Volume(pct, ratio))
        }
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
