package dev.gyro.engine.video

import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.VideoScale
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.HdrDisplayCapabilities
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.model.normalizeContentFps
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.SwitchTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Internal algebraic state tracking geometry availability across file switches to filter stale
 * video-output dimension updates before the new container headers / first frame arrive.
 */
internal sealed interface GeometrySessionState {
    data object Idle : GeometrySessionState
    data class Switching(val url: String, val startPositionMs: Long = 0L) : GeometrySessionState
    data class Demuxed(val url: String, val dimensions: VideoDimensions, val aspect: Float) : GeometrySessionState
    data class Presenting(val url: String, val dimensions: VideoDimensions, val aspect: Float) : GeometrySessionState
    data object Ended : GeometrySessionState
}

/**
 * Coordinates video dimensions, aspect ratio negotiation, and switch lifecycle transitions.
 * Guarantees that container layout adapts immediately on [GeometrySessionState.Demuxed]
 * without waiting for decoder warmup, while isolating stale VO properties during [GeometrySessionState.Switching].
 */
class VideoGeometryCoordinator(
    private val bridge: MpvNativeBridge,
    private val store: PlaybackStore = PlaybackStore()
) : dev.gyro.engine.EngineSubsystem, dev.gyro.engine.api.controller.GeometryController {

    private val _sessionState = MutableStateFlow<GeometrySessionState>(GeometrySessionState.Idle)
    internal val sessionState: StateFlow<GeometrySessionState> = _sessionState.asStateFlow()

    private val _videoDimensions = store.property({ it.videoDimensions }) { s, v -> s.copy(videoDimensions = v) }
    override val videoDimensions: StateFlow<VideoDimensions> = _videoDimensions.asStateFlow()

    private val _videoAspect = store.property({ it.videoAspect }) { s, v -> s.copy(videoAspect = v) }
    override val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoFrameRate = store.property({ it.videoFrameRate }) { s, v -> s.copy(videoFrameRate = v) }
    override val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    private val _geometryReady = store.property({ it.geometryReady }) { s, v -> s.copy(geometryReady = v) }
    override val geometryReady: StateFlow<Boolean> = _geometryReady.asStateFlow()

    private val _firstFrameRendered = store.property({ it.firstFrameRendered }) { s, v -> s.copy(firstFrameRendered = v) }
    override val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    // Session identity is owned by the bridge (Rust `G_SESSION_EPOCH`); events carry the epoch
    // they were produced under, so freshness is validated against `bridge.currentEpoch()` at
    // dispatch time instead of a locally cached copy that could trail a background load.

    @Volatile
    private var currentUrl = ""

    @Volatile
    private var cachedIsDoviTrack: Boolean? = null

    private val _displayRefreshRate = store.property({ it.displayRefreshRate }) { s, v -> s.copy(displayRefreshRate = v) }
    val displayRefreshRate: StateFlow<Float> = _displayRefreshRate.asStateFlow()

    private val _resizeMode = store.property({ it.resizeMode }) { s, v -> s.copy(resizeMode = v) }
    override val resizeMode: StateFlow<ResizeMode> = _resizeMode.asStateFlow()

    private val _isHdr = store.property({ it.isHdr }) { s, v -> s.copy(isHdr = v) }
    override val isHdr: StateFlow<Boolean> = _isHdr.asStateFlow()

    private val _hdrFormat = store.property({ it.hdrFormat }) { s, v -> s.copy(hdrFormat = v) }
    override val hdrFormat: StateFlow<String> = _hdrFormat.asStateFlow()

    /**
     * Panel capabilities as reported by the view.
     *
     * SDR-only until the view says otherwise: a missed report then degrades into an honest SDR
     * fold instead of a PQ surface that nothing on the panel can interpret.
     */
    @Volatile
    private var displayCapabilities = HdrDisplayCapabilities.SdrOnly

    /** Last target actually written, so a repeated report does not rewrite the colour pipeline. */
    private var appliedTarget: HdrOutputTarget? = null

    /** Last `tone-mapping` value written, `null` when the pipeline still holds the init option. */
    private var appliedToneMapping: String? = null

    override fun setDisplayCapabilities(capabilities: HdrDisplayCapabilities) {
        if (displayCapabilities == capabilities) return
        displayCapabilities = capabilities
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
        bridge.observeProperty("video-params/gamma", MpvNativeBridge.Format.STRING)
        bridge.observeProperty("video-params/colormatrix", MpvNativeBridge.Format.STRING)
        bridge.observeProperty("video-out-params", MpvNativeBridge.Format.NONE)
        bridge.observeProperty("track-list", MpvNativeBridge.Format.NONE)
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
                MpvNativeBridge.Event.TRACKS_CHANGED -> {
                    refreshDoviTrackCache()
                    updateHdrState()
                }
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
                "track-list" -> {
                    refreshDoviTrackCache()
                    updateHdrState()
                }
            }
            is MpvEvent.StringProperty -> when (event.name) {
                // The transfer function is only populated once the decoder has inspected the
                // stream, which can land after FILE_LOADED and after the first frame. Without
                // this observer such a file stayed SDR for its whole playback.
                "video-params/gamma", "video-params/colormatrix" -> updateHdrState()
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
        cachedIsDoviTrack = null
        _sessionState.value = GeometrySessionState.Switching(url, startPositionMs)
        _firstFrameRendered.value = false
        _videoFrameRate.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        resetHdrPipeline()
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
            cachedIsDoviTrack = false
            _sessionState.value = GeometrySessionState.Presenting(url, VideoDimensions(0, 0), 1.0f)
            _firstFrameRendered.value = true
            _geometryReady.value = true
            _isHdr.value = false
            _hdrFormat.value = ""
            return
        }

        // Prioritize demux dimensions from container track header (accurate and available immediately)
        val targetDims = getDemuxDimensions() ?: getVoDimensions()
        val (w, h) = targetDims ?: Pair(0, 0)
        val aspect = if (w > 0 && h > 0) w.toFloat() / h else 0f
        val dims = VideoDimensions(w, h)
        _sessionState.value = GeometrySessionState.Demuxed(url, dims, aspect)
        if (w > 0 && h > 0) {
            _videoDimensions.value = dims
            _videoAspect.value = aspect
            _geometryReady.value = true
            SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=demux")
        }

        refreshDoviTrackCache()
        updateHdrState()

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
            is GeometrySessionState.Switching -> current.url
            is GeometrySessionState.Demuxed -> current.url
            is GeometrySessionState.Presenting -> current.url
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
        _sessionState.value = GeometrySessionState.Presenting(url, dims, aspect)
        _firstFrameRendered.value = true
        _geometryReady.value = true
        // `video-params` frequently lands while the session is still Switching, where the HDR
        // decision is suppressed by design. Without this re-check the first frame of a cold start
        // would stay SDR until some later property event happened to fire again.
        updateHdrState()
    }
    fun onVoParamsChanged() {
        when (val state = _sessionState.value) {
            is GeometrySessionState.Switching -> {
                // VO still reports previous video's parameters during switch; suppress completely
                SwitchTrace.log("DIMS_IGNORED_SWITCH_PENDING", "")
                return
            }
            is GeometrySessionState.Demuxed -> {
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
                        _sessionState.value = GeometrySessionState.Demuxed(state.url, dims, aspect)
                        SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=vo_fallback")
                    }
                }
            }
            is GeometrySessionState.Presenting, is GeometrySessionState.Idle, is GeometrySessionState.Ended -> {
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
                    if (state is GeometrySessionState.Presenting) {
                        _sessionState.value = GeometrySessionState.Presenting(state.url, dims, aspect)
                    }
                    SwitchTrace.log("DIMS_PUBLISHED", "${w}x${h}, aspect=$aspect, source=vo_params")
                }
            }
        }
        updateHdrState()
    }
    fun onSessionEnded() {
        cachedIsDoviTrack = null
        _sessionState.value = GeometrySessionState.Ended
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        resetHdrPipeline()
    }

    override fun reset() {
        currentUrl = ""
        cachedIsDoviTrack = null
        _sessionState.value = GeometrySessionState.Idle
        _firstFrameRendered.value = false
        _geometryReady.value = false
        _videoFrameRate.value = 0f
        _videoDimensions.value = VideoDimensions(0, 0)
        _videoAspect.value = 0f
        _isHdr.value = false
        _hdrFormat.value = ""
        resetHdrPipeline()
    }

    private fun refreshDoviTrackCache() {
        cachedIsDoviTrack = queryIsDoviTrack()
    }

    private fun queryIsDoviTrack(): Boolean {
        val count = bridge.getLong("track-list/count") ?: 0L
        for (i in 0 until count) {
            if (bridge.getPropertyString("track-list/$i/type") == "video") {
                val codec = bridge.getPropertyString("track-list/$i/codec")?.lowercase() ?: ""
                val fName = bridge.getPropertyString("track-list/$i/format-name")?.lowercase() ?: ""
                if (codec.contains("dovi") || codec.contains("dvhe") || codec.contains("dvh1") ||
                    fName.contains("dovi") || fName.contains("dolby")
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun updateHdrState() {
        if (_sessionState.value is GeometrySessionState.Switching) {
            return
        }
        val format = resolveHdrFormat()
        val target = HdrOutputPlanner.plan(
            format = format,
            display = displayCapabilities,
            colorSpace = bridge.hdrColorSpaceSupport(),
            preference = store.current.hdrOutputMode,
        )
        applyHdrTarget(format, target)
    }

    /**
     * Classifies the source from `video-params`, which describes what the decoder actually
     * produced. `video-out-params` is deliberately not consulted: that is the renderer's output,
     * so reading it would make the HDR decision chase its own tail.
     */
    private fun resolveHdrFormat(): HdrFormat {
        val gamma = bridge.getPropertyString("video-params/gamma")?.lowercase()?.trim() ?: ""
        val colormatrix = bridge.getPropertyString("video-params/colormatrix")?.lowercase()?.trim() ?: ""
        val isDoviTrack = cachedIsDoviTrack ?: queryIsDoviTrack().also { cachedIsDoviTrack = it }
        val isDovi = colormatrix.contains("dolby") || colormatrix.contains("dovi") || isDoviTrack
        return when {
            isDovi -> HdrFormat.DOLBY_VISION
            gamma == "pq" -> HdrFormat.HDR10
            gamma == "hlg" -> HdrFormat.HLG
            else -> HdrFormat.NONE
        }
    }

    /**
     * Writes one [HdrOutputTarget] to the whole pipeline: the EGL surface selector, the mpv target
     * colours, and the tone curve. Single writer, so "参数只改了一个" cannot happen again.
     */
    private fun applyHdrTarget(format: HdrFormat, target: HdrOutputTarget) {
        val unchanged = _hdrFormat.value == format.label &&
            _isHdr.value == target.isHdr &&
            appliedTarget == target
        if (unchanged) return

        _hdrFormat.value = format.label
        _isHdr.value = target.isHdr
        appliedTarget = target

        // Native reads this to pick the window surface colours space, which must equal the curve
        // mpv is about to encode into it.
        bridge.setPropertyString("hdr-output", if (target.isHdr) target.transfer.mpvTrc else "no")
        bridge.setPropertyString("target-trc", target.transfer.mpvTrc)
        bridge.setPropertyString("target-prim", target.primaries)
        // Without a peak mpv cannot tell a 1000-nit panel from a 400-nit one and tone maps the
        // source before encoding PQ, flattening contrast.
        if (target.peakNits > 0f) {
            bridge.setPropertyDouble("target-peak", target.peakNits.toDouble())
        } else {
            bridge.setPropertyString("target-peak", "auto")
        }
        bridge.setPropertyString("gamut-clipping", if (target.gamutClipping) "yes" else "no")
        applyToneMapping()

        SwitchTrace.log(
            "HDR_STATE",
            "format=${format.label}, transfer=${target.transfer.mpvTrc}, prim=${target.primaries}, " +
                "peak=${target.peakNits}, fold=${target.foldsToSdr}, reason=${target.reason}"
        )
    }

    /** Returns the colour pipeline to SDR defaults, including the state tracking in front of it. */
    private fun resetHdrPipeline() {
        appliedTarget = null
        appliedToneMapping = null
        bridge.setPropertyString("hdr-output", "no")
        bridge.setPropertyString("target-trc", "auto")
        bridge.setPropertyString("target-prim", "auto")
        bridge.setPropertyString("target-peak", "auto")
        bridge.setPropertyString("gamut-clipping", "no")
    }

    /**
     * Updates the panel rate mpv paces against.
     *
     * Runtime callers (surface availability, display switch) can fire before the native core
     * exists, where the property write is a no-op; [dev.gyro.engine.GyroPlayerEngine] applies the
     * rate explicitly once the core is up, so an early measurement is not lost.
     */
    override fun updateDisplayRefreshRate(rate: Float) {
        if (rate !in 20f..300f) return
        if (abs(_displayRefreshRate.value - rate) < 0.1f) return
        _displayRefreshRate.value = rate
        bridge.setPropertyDouble("display-fps-override", rate.toDouble())
        bridge.setPropertyDouble("display-fps", rate.toDouble())
    }

    override fun setHdrOutputMode(mode: HdrOutputMode) {
        store.update { it.copy(hdrOutputMode = mode) }
        updateHdrState()
    }

    override fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) {
        store.update { it.copy(toneMappingAlgorithm = algorithm) }
        applyToneMapping()
    }

    /**
     * Pushes the curve mpv should use. [ToneMappingAlgorithm.AUTO] follows highQualityRendering:
     * `hable` when high quality is on, or standard `auto` when off.
     */
    private fun applyToneMapping() {
        val algorithm = store.current.toneMappingAlgorithm
        val effective = when {
            algorithm != ToneMappingAlgorithm.AUTO -> algorithm.persistedKey
            store.current.highQualityRendering -> "hable"
            else -> "auto"
        }
        if (effective == appliedToneMapping) return
        appliedToneMapping = effective
        bridge.setPropertyString("tone-mapping", effective)
    }

    override fun setVideoScale(scale: VideoScale) {
        store.update { it.copy(videoScale = scale) }
        val effective = when (scale) {
            VideoScale.BILINEAR -> if (store.current.highQualityRendering) "spline36" else "bilinear"
            VideoScale.LANCZOS -> "lanczos"
            VideoScale.SPLINE36 -> "spline36"
        }
        bridge.setPropertyString("scale", effective)
        bridge.setPropertyString("dscale", if (effective == "bilinear") "bilinear" else "mitchell")
    }

    override fun setInterpolation(enabled: Boolean) {
        store.update { it.copy(interpolation = enabled) }
        if (enabled) {
            bridge.setPropertyString("video-sync", "display-resample")
            bridge.setPropertyString("interpolation", "yes")
            bridge.setPropertyString("tscale", "oversample")
        } else {
            bridge.setPropertyString("video-sync", "audio")
            bridge.setPropertyString("interpolation", "no")
        }
    }

    override fun setResizeMode(mode: ResizeMode) {
        _resizeMode.value = mode
    }
    override fun resetZoomAndPan() {
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
