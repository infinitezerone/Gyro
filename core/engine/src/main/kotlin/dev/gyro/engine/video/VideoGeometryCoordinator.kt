package dev.gyro.engine.video

import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.session.PlaybackSessionState
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.ui.normalizeContentFps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Coordinates video dimensions, aspect ratio negotiation, and switch lifecycle transitions.
 * Guarantees that container layout adapts immediately on [PlaybackSessionState.Demuxed]
 * without waiting for decoder warmup, while isolating stale VO properties during [PlaybackSessionState.Switching].
 */
class VideoGeometryCoordinator(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : dev.gyro.engine.EngineSubsystem {

    private val _sessionState = store.property({ it.sessionState }) { s, v -> s.copy(sessionState = v) }
    val sessionState: StateFlow<PlaybackSessionState> = _sessionState.asStateFlow()

    private val _videoDimensions = store.property({ it.videoDimensions }) { s, v -> s.copy(videoDimensions = v) }
    val videoDimensions: StateFlow<VideoDimensions> = _videoDimensions.asStateFlow()

    private val _videoAspect = store.property({ it.videoAspect }) { s, v -> s.copy(videoAspect = v) }
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoFrameRate = store.property({ it.videoFrameRate }) { s, v -> s.copy(videoFrameRate = v) }
    val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    private val _geometryReady = store.property({ it.geometryReady }) { s, v -> s.copy(geometryReady = v) }
    val geometryReady: StateFlow<Boolean> = _geometryReady.asStateFlow()

    private val _firstFrameRendered = store.property({ it.firstFrameRendered }) { s, v -> s.copy(firstFrameRendered = v) }
    val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    // Session identity is owned by the bridge (Rust `G_SESSION_EPOCH`); events carry the epoch
    // they were produced under, so freshness is validated against `bridge.currentEpoch()` at
    // dispatch time instead of a locally cached copy that could trail a background load.

    @Volatile
    private var currentUrl = ""

    private val _displayRefreshRate = store.property({ it.displayRefreshRate }) { s, v -> s.copy(displayRefreshRate = v) }
    val displayRefreshRate: StateFlow<Float> = _displayRefreshRate.asStateFlow()

    private val _resizeMode = store.property({ it.resizeMode }) { s, v -> s.copy(resizeMode = v) }
    val resizeMode: StateFlow<ResizeMode> = _resizeMode.asStateFlow()

    private val _isHdr = store.property({ it.isHdr }) { s, v -> s.copy(isHdr = v) }
    val isHdr: StateFlow<Boolean> = _isHdr.asStateFlow()

    private val _hdrFormat = store.property({ it.hdrFormat }) { s, v -> s.copy(hdrFormat = v) }
    val hdrFormat: StateFlow<String> = _hdrFormat.asStateFlow()

    /**
     * Whether the panel can actually present HDR.
     *
     * Only the view can answer this (`Display.isHdr`), and without it an SDR panel would still be
     * told to output PQ: mpv would skip tone-mapping and the picture would turn washed out.
     * Defaults to true so a missed report keeps the previous behaviour rather than killing HDR.
     */
    @Volatile
    private var displayHdrCapable = true

    fun setDisplayHdrCapable(capable: Boolean) {
        if (displayHdrCapable == capable) return
        displayHdrCapable = capable
        updateHdrState()
    }

    fun onHdrOutputModeChanged() {
        updateHdrState()
    }

    override fun registerObservers() {
        bridge.observeProperty("dwidth", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("dheight", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("width", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("height", MpvNativeBridge.Format.INT64)
        bridge.observeProperty("container-fps", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("estimated-vf-fps", MpvNativeBridge.Format.DOUBLE)
        bridge.observeProperty("video-params", MpvNativeBridge.Format.NONE)
        bridge.observeProperty("video-out-params", MpvNativeBridge.Format.NONE)
    }

    override fun onEvent(event: MpvEvent) {
        val sessionEpoch = bridge.currentEpoch()
        if (event.epoch < sessionEpoch) {
            SwitchTrace.log("EVENT_IGNORED_EXPIRED_EPOCH", "eventEpoch=${event.epoch}, currentEpoch=$sessionEpoch, event=$event")
            return
        }
        when (event) {
            is MpvEvent.Lifecycle -> when (event.id) {
                MpvNativeBridge.Event.FILE_LOADED ->
                    onFileLoaded(currentUrl, bridge.getPropertyString("video") != "no")
                MpvNativeBridge.Event.FIRST_FRAME -> onFirstFramePresented()
                MpvNativeBridge.Event.END_FILE, MpvNativeBridge.Event.SHUTDOWN -> onSessionEnded()
            }
            is MpvEvent.EndFile -> when (event.reason) {
                MpvNativeBridge.EndReason.EOF,
                MpvNativeBridge.EndReason.QUIT,
                MpvNativeBridge.EndReason.ERROR -> onSessionEnded()
            }
            is MpvEvent.PropertyChanged -> when (event.name) {
                "video-params", "video-out-params" -> onVoParamsChanged()
            }
            is MpvEvent.LongProperty -> when (event.name) {
                "width", "height", "dwidth", "dheight" -> onVoParamsChanged()
            }
            is MpvEvent.DoubleProperty -> when (event.name) {
                "container-fps" -> onFpsUpdated(event.value.toFloat(), isEstimated = false)
                "estimated-vf-fps" ->
                    onFpsUpdated(normalizeContentFps(event.value.toFloat()), isEstimated = true)
            }
            else -> Unit
        }
    }
    fun observeEvents(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            bridge.events.collect(::onEvent)
        }
    fun onSwitchStarted(
        url: String,
        startPositionMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) {
        currentUrl = url
        _sessionState.value = PlaybackSessionState.Switching(url, startPositionMs)
        _firstFrameRendered.value = false
        _videoFrameRate.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        if (initialWidth > 0 && initialHeight > 0) {
            val aspect = initialWidth.toFloat() / initialHeight
            val dims = VideoDimensions(initialWidth, initialHeight)
            _videoDimensions.value = dims
            _videoAspect.value = aspect
            _geometryReady.value = true
            SwitchTrace.log("SWITCH_SESSION_START_PREWARMED", "url=$url, ${initialWidth}x${initialHeight}, aspect=$aspect")
        } else {
            _geometryReady.value = false
            _videoDimensions.value = VideoDimensions(0, 0)
            _videoAspect.value = 0f
            SwitchTrace.log("SWITCH_SESSION_START", "url=$url, startPos=${startPositionMs}ms")
        }
    }
    fun onFileLoaded(url: String, hasVideo: Boolean) {
        if (!hasVideo) {
            _sessionState.value = PlaybackSessionState.Presenting(url, VideoDimensions(0, 0), 1.0f)
            _firstFrameRendered.value = true
            _geometryReady.value = true
            _isHdr.value = false
            _hdrFormat.value = ""
            return
        }

        updateHdrState()

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
    fun onFpsUpdated(fps: Float, isEstimated: Boolean = false) {
        if (fps <= 1.0f || fps >= 1000.0f) return
        if (isEstimated) {
            if (_videoFrameRate.value <= 0f) {
                _videoFrameRate.value = fps
            }
        } else {
            _videoFrameRate.value = fps
        }
    }
    fun onFirstFramePresented() {
        val current = _sessionState.value
        var dims = _videoDimensions.value
        var aspect = _videoAspect.value
        val url = when (current) {
            is PlaybackSessionState.Switching -> current.url
            is PlaybackSessionState.Demuxed -> current.url
            is PlaybackSessionState.Presenting -> current.url
            else -> ""
        }
        if (dims.width <= 0 || dims.height <= 0) {
            val fallback = getVoDimensions() ?: getDemuxDimensions()
            if (fallback != null && fallback.first > 0 && fallback.second > 0) {
                dims = VideoDimensions(fallback.first, fallback.second)
                aspect = fallback.first.toFloat() / fallback.second
                _videoDimensions.value = dims
                _videoAspect.value = aspect
            }
        }
        _sessionState.value = PlaybackSessionState.Presenting(url, dims, aspect)
        _firstFrameRendered.value = true
        _geometryReady.value = true
    }
    fun onVoParamsChanged() {
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
        updateHdrState()
    }
    fun onSessionEnded() {
        _sessionState.value = PlaybackSessionState.Ended
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        bridge.setPropertyString("hdr-output", "no")
        bridge.setPropertyString("target-trc", "auto")
        bridge.setPropertyString("target-prim", "auto")
    }

    override fun reset() {
        currentUrl = ""
        _sessionState.value = PlaybackSessionState.Idle
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        _videoDimensions.value = VideoDimensions(0, 0)
        _videoAspect.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        bridge.setPropertyString("hdr-output", "no")
        bridge.setPropertyString("target-trc", "auto")
        bridge.setPropertyString("target-prim", "auto")
    }

    private fun updateHdrState() {
        val gamma = bridge.getPropertyString("video-params/gamma")?.lowercase()?.trim() ?: ""
        val colormatrix = bridge.getPropertyString("video-params/colormatrix")?.lowercase()?.trim() ?: ""

        var isDoviTrack = false
        val count = bridge.getLong("track-list/count") ?: 0L
        for (i in 0 until count) {
            if (bridge.getPropertyString("track-list/$i/type") == "video") {
                val codec = bridge.getPropertyString("track-list/$i/codec")?.lowercase() ?: ""
                val fName = bridge.getPropertyString("track-list/$i/format-name")?.lowercase() ?: ""
                if (codec.contains("dovi") || codec.contains("dvhe") || codec.contains("dvh1") ||
                    fName.contains("dovi") || fName.contains("dolby")) {
                    isDoviTrack = true
                    break
                }
            }
        }

        val isDovi = colormatrix.contains("dolby") || colormatrix.contains("dovi") || isDoviTrack
        val isPq = gamma == "pq"
        val isHlg = gamma == "hlg"

        val format = when {
            isDovi -> "Dolby Vision"
            isPq -> "HDR10"
            isHlg -> "HLG"
            else -> ""
        }

        val allowHdrLighting = store.current.hdrOutputMode != dev.gyro.core.model.HDR_OUTPUT_SDR &&
            displayHdrCapable

        // Standard HDR10 and HLG can be physically lit up by vo=gpu on 10-bit displays.
        // Dolby Vision Profile 5 (ICtCp) is skipped from PQ hardware lighting to avoid severe purple/green tint on vo_gpu.
        val canLightUp = !isDovi && (isPq || isHlg)
        val hdr = allowHdrLighting && canLightUp

        if (_isHdr.value != hdr || _hdrFormat.value != format) {
            _isHdr.value = hdr
            _hdrFormat.value = format
            bridge.setPropertyString("hdr-output", if (hdr) "yes" else "no")
            if (hdr) {
                bridge.setPropertyString("target-trc", "pq")
                bridge.setPropertyString("target-prim", "bt.2020")
            } else {
                bridge.setPropertyString("target-trc", "auto")
                bridge.setPropertyString("target-prim", "auto")
            }
            SwitchTrace.log("HDR_STATE", "isHdr=$hdr, format=$format, gamma=$gamma, colormatrix=$colormatrix")
        }
    }

    /**
     * Updates the panel rate mpv paces against.
     *
     * Runtime callers (surface availability, display switch) can fire before the native core
     * exists, where the property write is a no-op; [dev.gyro.engine.GyroPlayerEngine] applies the
     * rate explicitly once the core is up, so an early measurement is not lost.
     */
    fun updateDisplayRefreshRate(rate: Float) {
        if (rate !in 20f..300f) return
        if (abs(_displayRefreshRate.value - rate) < 0.1f) return
        _displayRefreshRate.value = rate
        bridge.setPropertyDouble("display-fps-override", rate.toDouble())
        bridge.setPropertyDouble("display-fps", rate.toDouble())
    }
    fun setResizeMode(mode: ResizeMode) {
        _resizeMode.value = mode
        when (mode) {
            ResizeMode.FIT -> {
                bridge.setPropertyDouble("panscan", 0.0)
                bridge.setPropertyString("video-aspect-override", "-1")
            }
            ResizeMode.CROP -> {
                bridge.setPropertyDouble("panscan", 1.0)
                bridge.setPropertyString("video-aspect-override", "-1")
            }
            ResizeMode.STRETCH -> {
                bridge.setPropertyDouble("panscan", 0.0)
                bridge.setPropertyString("video-aspect-override", "no")
            }
            ResizeMode.ASPECT_16_9 -> {
                bridge.setPropertyDouble("panscan", 0.0)
                bridge.setPropertyString("video-aspect-override", "16:9")
            }
            ResizeMode.ASPECT_21_9 -> {
                bridge.setPropertyDouble("panscan", 0.0)
                bridge.setPropertyString("video-aspect-override", "21:9")
            }
        }
    }
    fun resetZoomAndPan() {
        bridge.setPropertyDouble("video-zoom", 0.0)
        bridge.setPropertyDouble("video-pan-x", 0.0)
        bridge.setPropertyDouble("video-pan-y", 0.0)
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
