package dev.gyro.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
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
    fun onBrightnessChange(deltaBrightnessRatio: Float) {}
    fun onVolumeChange(deltaSteps: Int) {}
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

    private val activity = context as? Activity
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

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

    override fun onBrightnessChange(deltaBrightnessRatio: Float) {
        activity?.let { act ->
            val lp = act.window.attributes
            var cur = lp.screenBrightness
            if (cur < 0) cur = 0.5f
            val newBrightness = (cur + deltaBrightnessRatio).coerceIn(0.01f, 1.0f)
            lp.screenBrightness = newBrightness
            act.window.attributes = lp
            val pct = (newBrightness * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Brightness(pct, newBrightness))
        }
    }

    override fun onVolumeChange(deltaSteps: Int) {
        audioManager?.let { am ->
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val newVol = (curVol + deltaSteps).coerceIn(0, maxVolume)
            if (newVol != curVol) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            }
            val ratio = newVol.toFloat() / maxVolume.toFloat()
            val pct = (ratio * 100).toInt()
            onHudStateChange.invoke(GestureHudState.Volume(pct, ratio))
        }
    }

    override fun onHudStateChange(state: GestureHudState) {
        onHudStateChange.invoke(state)
    }
}
