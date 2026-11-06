package dev.gyro.engine.session

import android.os.SystemClock
import android.util.Log
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.gyro.engine.tracks.TrackAndChapterManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

interface PlaybackTimelineController {
    val timePosMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val bufferedPosMs: StateFlow<Long>
    val isFileLoaded: Boolean

    fun observeEvents(scope: CoroutineScope): Job
    fun onLoadStarted(startPositionMs: Long)
    fun onLoadCommandIssued()
    fun onFileLoaded()
    fun onPositionChanged(positionSeconds: Double)
    fun onDurationChanged(durationSeconds: Double)
    fun onBufferedPositionChanged(positionSeconds: Double)
    fun seekTo(positionMs: Long)
    fun seekRelative(offsetMs: Long)
    fun seekToChapter(index: Int)
}

@Singleton
class RealPlaybackTimelineController internal constructor(
    private val bridge: MpvNativeBridge,
    private val geometry: VideoGeometryCoordinator,
    private val trackManager: TrackAndChapterManager,
    private val elapsedRealtimeMs: () -> Long,
    private val store: PlaybackStore = PlaybackStore()
) : PlaybackTimelineController {
    @Inject
    constructor(
        bridge: MpvNativeBridge,
        geometry: VideoGeometryCoordinator,
        trackManager: TrackAndChapterManager,
        store: PlaybackStore
    ) : this(bridge, geometry, trackManager, SystemClock::elapsedRealtime, store)

    companion object {
        private const val TAG = "PlaybackTimelineController"
        // mpv may land on a neighbouring keyframe instead of the exact target.
        private const val SEEK_SETTLE_TOLERANCE_MS = 1500L
        private const val SEEK_SETTLE_TIMEOUT_MS = 2500L
    }

    private val _timePosMs = MutableStateFlow(0L)
    override val timePosMs: StateFlow<Long> = _timePosMs.asStateFlow()

    private val _durationMs = store.property({ it.durationMs }) { s, v -> s.copy(durationMs = v) }
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPosMs = MutableStateFlow(0L)
    override val bufferedPosMs: StateFlow<Long> = _bufferedPosMs.asStateFlow()

    override val isFileLoaded: Boolean
        get() = when (geometry.sessionState.value) {
            is PlaybackSessionState.Demuxed, is PlaybackSessionState.Presenting -> true
            else -> false
        }

    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekIssuedElapsedMs = 0L

    @Volatile
    private var pendingSeekMs = -1L

    override fun observeEvents(scope: CoroutineScope): Job = scope.launch(
        Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED
    ) {
        bridge.events.collect { event ->
            when (event) {
                is MpvEvent.Lifecycle -> if (event.id == MpvNativeBridge.Event.FILE_LOADED) {
                    onFileLoaded()
                }
                is MpvEvent.DoubleProperty -> when (event.name) {
                    "time-pos" -> onPositionChanged(event.value)
                    "duration" -> onDurationChanged(event.value)
                    "demuxer-cache-time" -> onBufferedPositionChanged(event.value)
                }
                else -> Unit
            }
        }
    }

    override fun onLoadStarted(startPositionMs: Long) {
        pendingSeekTargetMs = null
        _timePosMs.value = startPositionMs.coerceAtLeast(0L)
        _durationMs.value = 0L
    }

    override fun onLoadCommandIssued() {
        pendingSeekMs = -1L
    }

    override fun onFileLoaded() {
        if (pendingSeekMs >= 0L) {
            val target = pendingSeekMs
            pendingSeekMs = -1L
            // FILE_LOADED is authoritative even if geometry has not consumed the same event yet.
            seekAbsolute(target)
        }
    }

    override fun onPositionChanged(positionSeconds: Double) {
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

    override fun onDurationChanged(durationSeconds: Double) {
        _durationMs.value = (durationSeconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    override fun onBufferedPositionChanged(positionSeconds: Double) {
        _bufferedPosMs.value = (positionSeconds * 1000.0).toLong().coerceAtLeast(_timePosMs.value)
    }

    override fun seekTo(positionMs: Long) {
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

    override fun seekRelative(offsetMs: Long) {
        if (!isFileLoaded) return
        // Chained seeks build on the displayed target, not mpv's lagging position.
        val targetMs = (_timePosMs.value + offsetMs).coerceAtLeast(0L)
        beginPendingSeek(targetMs)
        val seconds = offsetMs / 1000.0
        bridge.command(arrayOf("seek", "$seconds", "relative"))
    }

    override fun seekToChapter(index: Int) {
        val chapterList = trackManager.chapters.value
        if (index !in chapterList.indices) {
            Log.w(TAG, "seekToChapter: index $index out of bounds (0..${chapterList.size - 1})")
            return
        }
        if (!isFileLoaded) {
            pendingSeekMs = chapterList[index].timeMs
            return
        }
        beginPendingSeek(chapterList[index].timeMs)
        bridge.setPropertyLong("chapter", index.toLong())
        trackManager.onChapterPropertyChanged(index)
        Log.i(TAG, "seekToChapter: jumped to chapter $index (${chapterList[index].title})")
    }

    private fun beginPendingSeek(targetMs: Long) {
        val target = targetMs.coerceAtLeast(0L)
        pendingSeekTargetMs = target
        pendingSeekIssuedElapsedMs = elapsedRealtimeMs()
        _timePosMs.value = target
    }
}
