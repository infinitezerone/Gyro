package dev.gyro.engine.session

import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.video.VideoGeometryCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

open class PlaybackTimelineController internal constructor(
    private val bridge: MpvNativeBridge,
    private val geometry: VideoGeometryCoordinator? = null,
    private val trackManager: TrackAndChapterManager,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem {
    constructor(
        bridge: MpvNativeBridge,
        geometry: VideoGeometryCoordinator,
        trackManager: TrackAndChapterManager,
        store: PlaybackStore
    ) : this(bridge, geometry, trackManager, { System.nanoTime() / 1_000_000L }, store)

    companion object {
        private const val TAG = "PlaybackTimelineController"
        // mpv may land on a neighbouring keyframe instead of the exact target.
        private const val SEEK_SETTLE_TOLERANCE_MS = 1500L
        private const val SEEK_SETTLE_TIMEOUT_MS = 2500L
    }

    private val _timePosMs = MutableStateFlow(0L)
    val timePosMs: StateFlow<Long> = _timePosMs.asStateFlow()

    private val _durationMs = store.property({ it.durationMs }) { s, v -> s.copy(durationMs = v) }
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPosMs = MutableStateFlow(0L)
    val bufferedPosMs: StateFlow<Long> = _bufferedPosMs.asStateFlow()

    @Volatile
    private var fileLoaded = false

    @Volatile
    private var loadedAtGeometryState: PlaybackSessionState? = null

    val isFileLoaded: Boolean
        get() = when (store.current.session) {
            is SessionPhase.Loaded -> true
            is SessionPhase.Resolving,
            is SessionPhase.AwaitingSurface,
            is SessionPhase.Submitting,
            is SessionPhase.Failed -> false
            SessionPhase.Idle -> {
                val geom = geometry
                if (geom == null) {
                    fileLoaded
                } else {
                    when (val geomState = geom.sessionState.value) {
                        is PlaybackSessionState.Demuxed, is PlaybackSessionState.Presenting -> true
                        else -> if (fileLoaded && geomState == loadedAtGeometryState) {
                            true
                        } else {
                            fileLoaded = false
                            false
                        }
                    }
                }
            }
        }

    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekIssuedElapsedMs = 0L

    @Volatile
    private var pendingSeekMs = -1L

    override fun registerObservers() {
        bridge.observeProperty("time-pos", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("duration", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("demuxer-cache-time", MpvNativeBridge.Format.DOUBLE)
    }

    override fun onEvent(event: MpvEvent) {
        when (event) {
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.FILE_LOADED -> onFileLoaded()
                MpvNativeBridge.Event.END_FILE -> {
                    fileLoaded = false
                    loadedAtGeometryState = null
                    _bufferedPosMs.value = 0L
                    val dur = _durationMs.value
                    if (dur > 0L) {
                        _timePosMs.value = dur
                    }
                }
                MpvNativeBridge.Event.SHUTDOWN -> {
                    fileLoaded = false
                    loadedAtGeometryState = null
                    _bufferedPosMs.value = 0L
                }
            }
            is MpvEvent.EndFile -> when (event.reason) {
                MpvNativeBridge.EndReason.EOF -> {
                    fileLoaded = false
                    loadedAtGeometryState = null
                    _bufferedPosMs.value = 0L
                    val dur = _durationMs.value
                    if (dur > 0L) {
                        _timePosMs.value = dur
                    }
                }
                MpvNativeBridge.EndReason.QUIT,
                MpvNativeBridge.EndReason.ERROR -> {
                    fileLoaded = false
                    loadedAtGeometryState = null
                    _bufferedPosMs.value = 0L
                }
            }
            is MpvEvent.DoubleProperty -> when (event.name) {
                "time-pos" -> onPositionChanged(event.value)
                "duration" -> onDurationChanged(event.value)
                "demuxer-cache-time" -> onBufferedPositionChanged(event.value)
            }
            else -> Unit
        }
    }

    override fun reset() {}

    fun observeEvents(scope: CoroutineScope): Job = scope.launch(
        Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED
    ) {
        bridge.events.collect(::onEvent)
    }

    fun onLoadStarted(startPositionMs: Long, initialDurationMs: Long = 0L) {
        fileLoaded = false
        loadedAtGeometryState = null
        pendingSeekTargetMs = null
        val startMs = startPositionMs.coerceAtLeast(0L)
        _timePosMs.value = startMs
        _durationMs.value = initialDurationMs.coerceAtLeast(0L)
        _bufferedPosMs.value = startMs
    }

    fun onLoadCommandIssued() {
        pendingSeekMs = -1L
    }

    fun onFileLoaded() {
        fileLoaded = true
        loadedAtGeometryState = geometry?.sessionState?.value
        if (pendingSeekMs >= 0L) {
            val target = pendingSeekMs
            pendingSeekMs = -1L
            // FILE_LOADED is authoritative even if geometry has not consumed the same event yet.
            seekAbsolute(target)
        }
    }

    fun onPositionChanged(positionSeconds: Double) {
        val positionMs = (positionSeconds * 1000.0).toLong().coerceAtLeast(0L)
        val target = pendingSeekTargetMs
        if (target != null) {
            val reached = abs(positionMs - target) <= SEEK_SETTLE_TOLERANCE_MS
            val stale = elapsedRealtimeMs() - pendingSeekIssuedElapsedMs >= SEEK_SETTLE_TIMEOUT_MS
            // Pre-seek reports must not pull the displayed position back before mpv lands.
            if (!reached && !stale) return
            pendingSeekTargetMs = null
        }
        _timePosMs.value = positionMs
    }

    fun onDurationChanged(durationSeconds: Double) {
        _durationMs.value = (durationSeconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    fun onBufferedPositionChanged(positionSeconds: Double) {
        val rawMs = (positionSeconds * 1000.0).toLong().coerceAtLeast(_timePosMs.value)
        val dur = _durationMs.value
        _bufferedPosMs.value = if (dur > 0L) rawMs.coerceAtMost(dur) else rawMs
    }

    fun seekTo(positionMs: Long) {
        val dur = _durationMs.value
        if (store.current.playbackState == dev.gyro.engine.model.PlaybackState.ENDED && (dur <= 0L || positionMs < dur - 1000L)) {
            store.update { it.copy(playbackState = dev.gyro.engine.model.PlaybackState.READY) }
        }
        if (!isFileLoaded) {
            pendingSeekMs = positionMs
            return
        }
        seekAbsolute(positionMs)
    }

    private fun seekAbsolute(positionMs: Long) {
        beginPendingSeek(positionMs)
        val seconds = positionMs / 1000.0
        bridge.command(arrayOf("seek", "$seconds", "absolute"))
    }

    fun seekRelative(offsetMs: Long) {
        if (!isFileLoaded) return
        // Chained seeks build on the displayed target, not mpv's lagging position.
        val targetMs = (_timePosMs.value + offsetMs).coerceAtLeast(0L)
        beginPendingSeek(targetMs)
        val seconds = offsetMs / 1000.0
        bridge.command(arrayOf("seek", "$seconds", "relative"))
    }

    fun seekToChapter(index: Int) {
        val chapterList = trackManager.chapters.value
        if (index !in chapterList.indices) {
            EngineLog.w(TAG, "seekToChapter: index $index out of bounds (0..${chapterList.size - 1})")
            return
        }
        if (!isFileLoaded) {
            pendingSeekMs = chapterList[index].timeMs
            return
        }
        beginPendingSeek(chapterList[index].timeMs)
        bridge.setPropertyLong("chapter", index.toLong())
        trackManager.onChapterPropertyChanged(index)
        EngineLog.i(TAG, "seekToChapter: jumped to chapter $index (${chapterList[index].title})")
    }

    private fun beginPendingSeek(targetMs: Long) {
        val target = targetMs.coerceAtLeast(0L)
        pendingSeekTargetMs = target
        pendingSeekIssuedElapsedMs = elapsedRealtimeMs()
        _timePosMs.value = target
    }
}

@Deprecated("Use PlaybackTimelineController directly", ReplaceWith("PlaybackTimelineController"))
typealias RealPlaybackTimelineController = PlaybackTimelineController
