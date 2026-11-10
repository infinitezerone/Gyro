package dev.gyro.engine.session

import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.audio.AudioFocusController
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import dev.gyro.engine.trace.SwitchTrace

class MpvLoadException(val reason: Int, message: String) : RuntimeException(message)

/**
 * Controller responsible for core playback actions (play, pause, speed, hwdec)
 * and mapping mpv playback lifecycle events into [PlaybackStore].
 */
open class PlaybackLifecycleController(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore,
    private val audioFocusManager: AudioFocusController,
    private val getTimePosMs: () -> Long,
    private val getRequestedTitle: () -> String = { "" },
    private val onReplay: () -> Unit = NoOpReplay
) : EngineSubsystem {

    companion object {
        private const val TAG = "PlaybackLifecycle"
        private val NoOpReplay: () -> Unit = {}

        internal fun currentSessionTarget(phase: SessionPhase): String = when (phase) {
            is SessionPhase.Submitting -> phase.target
            is SessionPhase.AwaitingSurface -> phase.target
            is SessionPhase.Resolving -> phase.target
            is SessionPhase.Loaded -> phase.target
            is SessionPhase.Failed -> phase.target.orEmpty()
            SessionPhase.Idle -> ""
        }
    }

    override fun registerObservers() {
        bridge.observeProperty("pause", MpvNativeBridge.Format.FLAG)
        bridge.observeProperty("paused-for-cache", MpvNativeBridge.Format.FLAG)
        bridge.observeProperty("hwdec-current", MpvNativeBridge.Format.STRING)
        bridge.observeProperty("speed", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("frame-drop-count", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("vsync-ratio", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("media-title", MpvNativeBridge.Format.STRING)
        bridge.observeProperty("video-bitrate", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("audio-bitrate", MpvNativeBridge.Format.INT64)
    }

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.FIRST_FRAME -> {
                    val pos = getTimePosMs()
                    SwitchTrace.log("KOTLIN_EVENT_FIRST_FRAME", "timePos=${pos}ms")
                    EngineLog.i(TAG, "First frame presented at ${pos}ms")
                }
                MpvNativeBridge.Event.FILE_LOADED -> {
                    SwitchTrace.log("KOTLIN_EVENT_FILE_LOADED", "")
                    val hwdec = bridge.getPropertyString("hwdec-current") ?: "none"
                    val mpvTitle = bridge.getPropertyString("media-title")?.takeIf { it.isNotBlank() }
                    val isUnpaused = bridge.getBoolean("pause")?.let { !it }

                    store.update { state ->
                        val target = currentSessionTarget(state.session)
                        val title = if (getRequestedTitle().isBlank() && mpvTitle != null) {
                            mpvTitle
                        } else {
                            state.mediaTitle
                        }
                        state.copy(
                            session = SessionPhase.Loaded(target),
                            error = null,
                            hwdecCurrent = hwdec,
                            mediaTitle = title,
                            playbackState = PlaybackState.READY,
                            isPlaying = isUnpaused ?: state.isPlaying
                        )
                    }
                }
                MpvNativeBridge.Event.END_FILE -> {
                    store.update {
                        it.copy(isPlaying = false, playbackState = PlaybackState.ENDED)
                    }
                }
                MpvNativeBridge.Event.SHUTDOWN -> {
                    store.update {
                        it.copy(isPlaying = false, playbackState = PlaybackState.IDLE)
                    }
                }
                MpvNativeBridge.Event.PLAYBACK_RESTART -> {
                    store.update {
                        it.copy(playbackState = PlaybackState.READY)
                    }
                }
            }
            is MpvEvent.EndFile -> when (event.reason) {
                MpvNativeBridge.EndReason.EOF -> {
                    store.update {
                        it.copy(isPlaying = false, playbackState = PlaybackState.ENDED)
                    }
                }
                MpvNativeBridge.EndReason.ERROR -> {
                    val target = currentSessionTarget(store.current.session)
                    val cause = MpvLoadException(
                        reason = event.reason,
                        message = "mpv failed to load or play media (reason=${event.reason}, target=$target)"
                    )
                    store.update {
                        it.copy(
                            session = SessionPhase.Failed(target.ifBlank { null }, cause),
                            error = cause,
                            playbackState = PlaybackState.ERROR,
                            isPlaying = false
                        )
                    }
                }
                MpvNativeBridge.EndReason.QUIT -> {
                    store.update {
                        it.copy(isPlaying = false, playbackState = PlaybackState.IDLE)
                    }
                }
                MpvNativeBridge.EndReason.STOP,
                MpvNativeBridge.EndReason.REDIRECT -> {
                    // STOP is emitted when `loadfile ... replace` tears down the previous item;
                    // do not overwrite the new item's Resolving/Submitting phase.
                }
            }
            is MpvEvent.LongProperty -> when (event.name) {
                "frame-drop-count" -> store.update { it.copy(droppedFrames = event.value) }
                "video-bitrate" -> store.update { it.copy(videoBitrate = event.value) }
                "audio-bitrate" -> store.update { it.copy(audioBitrate = event.value) }
            }
            is MpvEvent.DoubleProperty -> when (event.name) {
                "speed" -> store.update { it.copy(speed = event.value.toFloat()) }
                "estimated-vf-fps" -> store.update { it.copy(fps = event.value) }
                "vsync-ratio" -> store.update { it.copy(vsyncRatio = event.value) }
            }
            is MpvEvent.FlagProperty -> {
                when (event.name) {
                    "pause" -> {
                        store.update {
                            it.copy(
                                isPlaying = !event.value,
                                playbackState = if (!event.value && it.playbackState != PlaybackState.BUFFERING) {
                                    PlaybackState.READY
                                } else {
                                    it.playbackState
                                }
                            )
                        }
                    }
                    "paused-for-cache" -> {
                        store.update {
                            if (event.value) {
                                it.copy(playbackState = PlaybackState.BUFFERING)
                            } else if (it.isPlaying) {
                                it.copy(playbackState = PlaybackState.READY)
                            } else {
                                it
                            }
                        }
                    }
                }
            }
            is MpvEvent.StringProperty -> {
                when (event.name) {
                    "hwdec-current" -> store.update { it.copy(hwdecCurrent = event.value) }
                    "media-title" -> if (event.value.isNotBlank() && getRequestedTitle().isBlank()) {
                        store.update { it.copy(mediaTitle = event.value) }
                    }
                }
            }
            is MpvEvent.PropertyChanged -> Unit
        }
    }

    override fun reset() {
        release()
    }

    fun play() {
        val currentDuration = store.current.durationMs
        val currentPos = getTimePosMs()
        val isAtEnd = store.current.playbackState == PlaybackState.ENDED ||
            (currentDuration > 0L && currentPos >= currentDuration - 500L)

        if (isAtEnd) {
            EngineLog.i(TAG, "play(): video is at end, replaying from start")
            if (onReplay !== NoOpReplay) {
                onReplay()
            } else {
                bridge.command(arrayOf("seek", "0", "absolute"))
            }
            store.update { it.copy(playbackState = PlaybackState.READY) }
        }

        if (!audioFocusManager.requestPlay()) {
            EngineLog.i(TAG, "play(): playback suppressed until audio focus is granted")
            return
        }
        bridge.setPropertyBoolean("pause", false)
    }

    fun pause() {
        audioFocusManager.notifyUserPaused()
        bridge.setPropertyBoolean("pause", true)
    }

    fun togglePlayPause() {
        if (store.current.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun setSpeed(speed: Float) {
        bridge.setPropertyDouble("speed", speed.toDouble().coerceIn(0.25, 4.0))
    }

    fun toggleHwdec() {
        val current = store.current.hwdecCurrent
        val isHw = current.contains("mediacodec")
        val target = if (isHw) "no" else (if (bridge.isZeroCopySafe()) "mediacodec,mediacodec-copy" else "mediacodec-copy")
        bridge.setPropertyString("hwdec", target)
        store.update { it.copy(hwdecCurrent = target) }
    }

    fun setHwdec(target: String) {
        bridge.setPropertyString("hwdec", target)
        store.update { it.copy(hwdecCurrent = target) }
    }

    fun cycleHwdec(): String {
        val current = store.current.hwdecCurrent
        val next = when {
            current.contains("copy") -> "no"
            current == "no" || current == "none" -> if (bridge.isZeroCopySafe()) "mediacodec" else "mediacodec-copy"
            else -> "mediacodec-copy"
        }
        setHwdec(next)
        return next
    }

    fun abandonAudioFocus() {
        audioFocusManager.abandon()
    }

    /** Clears a sticky released state so a re-initialized engine can request focus again. */
    fun resetAudioFocus() {
        audioFocusManager.reset()
    }

    fun release() {
        audioFocusManager.release()
    }
}
