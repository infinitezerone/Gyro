package dev.gyro.engine.session

import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.trace.SwitchTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Coordinates video dimensions, aspect ratio negotiation, and switch lifecycle transitions.
 * Guarantees that container layout adapts immediately on [PlaybackSessionState.Demuxed]
 * without waiting for decoder warmup, while isolating stale VO properties during [PlaybackSessionState.Switching].
 */
interface VideoGeometryCoordinator {
    val sessionState: StateFlow<PlaybackSessionState>
    val videoDimensions: StateFlow<VideoDimensions>
    val videoAspect: StateFlow<Float>
    val videoFrameRate: StateFlow<Float>
    val geometryReady: StateFlow<Boolean>
    val firstFrameRendered: StateFlow<Boolean>

    fun onSwitchStarted(url: String, startPositionMs: Long = 0L)
    fun onFileLoaded(url: String, hasVideo: Boolean)
    fun onFirstFramePresented()
    fun onVoParamsChanged()
    fun onFpsUpdated(fps: Float, isEstimated: Boolean = false)
    fun onSessionEnded()
    fun reset()
}

class RealVideoGeometryCoordinator(
    private val bridge: MpvNativeBridge
) : VideoGeometryCoordinator {

    private val _sessionState = MutableStateFlow<PlaybackSessionState>(PlaybackSessionState.Idle)
    override val sessionState: StateFlow<PlaybackSessionState> = _sessionState.asStateFlow()

    private val _videoDimensions = MutableStateFlow(VideoDimensions(0, 0))
    override val videoDimensions: StateFlow<VideoDimensions> = _videoDimensions.asStateFlow()

    private val _videoAspect = MutableStateFlow(0f)
    override val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoFrameRate = MutableStateFlow(0f)
    override val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    private val _geometryReady = MutableStateFlow(false)
    override val geometryReady: StateFlow<Boolean> = _geometryReady.asStateFlow()

    private val _firstFrameRendered = MutableStateFlow(false)
    override val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    override fun onSwitchStarted(url: String, startPositionMs: Long) {
        _sessionState.value = PlaybackSessionState.Switching(url, startPositionMs)
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        SwitchTrace.log("SWITCH_SESSION_START", "url=$url, startPos=${startPositionMs}ms")
    }

    override fun onFileLoaded(url: String, hasVideo: Boolean) {
        if (!hasVideo) {
            _sessionState.value = PlaybackSessionState.Presenting(url, VideoDimensions(0, 0), 1.0f)
            _firstFrameRendered.value = true
            _geometryReady.value = true
            return
        }

        // Prioritize demux dimensions from container track header (accurate and available immediately)
        val targetDims = getDemuxDimensions() ?: getVoDimensions()
        val (w, h) = targetDims ?: Pair(0, 0)
        val aspect = if (w > 0 && h > 0) w.toFloat() / h else 0f
        val dims = VideoDimensions(w, h)
        _sessionState.value = PlaybackSessionState.Demuxed(url, dims, aspect)
        if (w > 0 && h > 0) {
            _videoDimensions.value = dims
            _videoAspect.value = aspect
            _geometryReady.value = true
            SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=demux")
        }

        bridge.getDouble("container-fps")?.let { fps ->
            if (fps > 1.0 && fps < 1000.0) {
                _videoFrameRate.value = fps.toFloat()
            }
        }
    }

    override fun onFpsUpdated(fps: Float, isEstimated: Boolean) {
        if (fps <= 1.0f || fps >= 1000.0f) return
        if (isEstimated) {
            if (_videoFrameRate.value <= 0f) {
                _videoFrameRate.value = fps
            }
        } else {
            _videoFrameRate.value = fps
        }
    }

    override fun onFirstFramePresented() {
        val current = _sessionState.value
        val dims = _videoDimensions.value
        val aspect = _videoAspect.value
        val url = when (current) {
            is PlaybackSessionState.Switching -> current.url
            is PlaybackSessionState.Demuxed -> current.url
            is PlaybackSessionState.Presenting -> current.url
            else -> ""
        }
        _sessionState.value = PlaybackSessionState.Presenting(url, dims, aspect)
        _firstFrameRendered.value = true
    }

    override fun onVoParamsChanged() {
        when (val state = _sessionState.value) {
            is PlaybackSessionState.Switching -> {
                // VO still reports previous video's parameters during switch; suppress completely
                SwitchTrace.log("DIMS_IGNORED_SWITCH_PENDING", "")
            }
            is PlaybackSessionState.Demuxed -> {
                // If demux dims were not found (0x0), fallback to VO when it becomes available
                if (_videoDimensions.value.width == 0 || _videoDimensions.value.height == 0) {
                    val voDims = getVoDimensions() ?: return
                    val (w, h) = voDims
                    if (w > 0 && h > 0) {
                        val aspect = w.toFloat() / h
                        val dims = VideoDimensions(w, h)
                        _videoDimensions.value = dims
                        _videoAspect.value = aspect
                        _geometryReady.value = true
                        _sessionState.value = PlaybackSessionState.Demuxed(state.url, dims, aspect)
                        SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=vo_fallback")
                    }
                }
            }
            is PlaybackSessionState.Presenting, is PlaybackSessionState.Idle, is PlaybackSessionState.Ended -> {
                // Normal active playback, or pre-playback property configuration:
                val voDims = getVoDimensions() ?: return
                val (w, h) = voDims
                if (w <= 0 || h <= 0) return
                val current = _videoDimensions.value
                if (current.width != w || current.height != h) {
                    val aspect = w.toFloat() / h
                    val dims = VideoDimensions(w, h)
                    _videoDimensions.value = dims
                    _videoAspect.value = aspect
                    _geometryReady.value = true
                    if (state is PlaybackSessionState.Presenting) {
                        _sessionState.value = PlaybackSessionState.Presenting(state.url, dims, aspect)
                    }
                    SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=vo_params")
                }
            }
        }
    }

    override fun onSessionEnded() {
        _sessionState.value = PlaybackSessionState.Ended
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
    }

    override fun reset() {
        _sessionState.value = PlaybackSessionState.Idle
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        _videoDimensions.value = VideoDimensions(0, 0)
        _videoAspect.value = 0f
    }

    private fun getDemuxDimensions(): Pair<Int, Int>? {
        val count = bridge.getLong("track-list/count") ?: 0L
        for (i in 0 until count) {
            val type = bridge.getPropertyString("track-list/$i/type")
            if (type == "video") {
                val w = bridge.getLong("track-list/$i/demux-w")?.toInt() ?: 0
                val h = bridge.getLong("track-list/$i/demux-h")?.toInt() ?: 0
                val rot = bridge.getLong("track-list/$i/demux-rotation") ?: 0L
                val par = bridge.getDouble("track-list/$i/demux-par") ?: 1.0
                if (w > 0 && h > 0) {
                    val displayW = if (par > 0.01 && abs(par - 1.0) > 0.01) {
                        (w * par).toInt()
                    } else {
                        w
                    }
                    val quarterTurn = rot % 180L != 0L
                    val finalW = if (quarterTurn) h else displayW
                    val finalH = if (quarterTurn) displayW else h
                    return Pair(finalW, finalH)
                }
            }
        }
        return null
    }

    private fun getVoDimensions(): Pair<Int, Int>? {
        val voW = bridge.getLong("video-out-params/dw")?.toInt()?.takeIf { it > 0 }
        val voH = bridge.getLong("video-out-params/dh")?.toInt()?.takeIf { it > 0 }
        val voRotate = bridge.getLong("video-out-params/rotate")
            ?: bridge.getLong("video-params/rotate")
            ?: 0L
        val voQuarterTurn = voRotate % 180L != 0L
        return if (voW != null && voH != null) {
            if (voQuarterTurn) Pair(voH, voW) else Pair(voW, voH)
        } else {
            null
        }
    }
}
