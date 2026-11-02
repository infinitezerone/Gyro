package dev.gyro.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.Surface
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.core.model.MediaInfo
import dev.gyro.engine.audio.GyroAudioFocusManager
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackInfo
import dev.gyro.engine.model.VideoDimensions
import dev.gyro.engine.shader.ShaderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GyroPlayerEngine @JvmOverloads constructor(
    private val context: Context,
    private val bridge: MpvNativeBridge = RealMpvNativeBridge,
    private val mpvConfigRepository: MpvConfigRepository? = runCatching { MpvConfigRepository(context) }.getOrNull(),
    private val settingsRepository: SettingsRepository? = runCatching { SettingsRepository(context) }.getOrNull(),
    private val shaderManager: ShaderManager = ShaderManager(context.applicationContext ?: context)
) {
    companion object {
        private const val TAG = "GyroPlayerEngine"

        /** Protected keys that user mpv.conf MUST NOT override to avoid crashing native pipeline */
        val PROTECTED_OPTION_KEYS: Set<String> = setOf(
            "vo",
            "idle",
            "wid",
            "keep-open",
            "gpu-context"
        )

        const val FILTER_STEREO_DOWNMIX =
            "lavfi=[pan=stereo|c0=0.5*c2+0.707*c0+0.707*c4|c1=0.5*c2+0.707*c1+0.707*c5]"
        const val FILTER_DYNAUDNORM =
            "lavfi=[dynaudnorm=f=200:g=15:m=10:r=0.9]"
        const val FILTER_ALIMITER =
            "lavfi=[alimiter=limit=0.95:attack=5:release=50:asc=true]"
    }

    private val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
    private val isInitialized = AtomicBoolean(false)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Audio focus lives here rather than in the ViewModel or the service so that the same state
     * machine covers foreground playback, background audio and media-button resumes.
     */
    private val audioFocusManager = GyroAudioFocusManager(
        context = appContext,
        listener = object : GyroAudioFocusManager.Listener {
            override fun onPlayRequested() {
                bridge.setPropertyBoolean("pause", false)
            }

            override fun onPauseRequested() {
                bridge.setPropertyBoolean("pause", true)
            }
        }
    )

    // Playback Flow states
    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _timePosMs = MutableStateFlow(0L)
    val timePosMs: StateFlow<Long> = _timePosMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPosMs = MutableStateFlow(0L)
    val bufferedPosMs: StateFlow<Long> = _bufferedPosMs.asStateFlow()

    private val _videoDimensions = MutableStateFlow(VideoDimensions())
    val videoDimensions: StateFlow<VideoDimensions> = _videoDimensions.asStateFlow()

    private val _mediaTitle = MutableStateFlow("")
    val mediaTitle: StateFlow<String> = _mediaTitle.asStateFlow()

    /**
     * Title supplied by the caller (content resolver display name, or derived from the URI).
     *
     * mpv reports `media-title` from whatever it was handed, and for `fdclose://` loads that is the
     * bare descriptor number, so the player HUD, notification and media session would all show
     * "153" instead of the file name. While this is non-blank it wins over mpv's own metadata.
     */
    @Volatile
    private var requestedTitle: String = ""

    private val _hwdecCurrent = MutableStateFlow("")
    val hwdecCurrent: StateFlow<String> = _hwdecCurrent.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    private val _resizeMode = MutableStateFlow(ResizeMode.FIT)
    val resizeMode: StateFlow<ResizeMode> = _resizeMode.asStateFlow()

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private val _droppedFrames = MutableStateFlow(0L)
    val droppedFrames: StateFlow<Long> = _droppedFrames.asStateFlow()

    private val _subDelay = MutableStateFlow(0.0)
    val subDelay: StateFlow<Double> = _subDelay.asStateFlow()

    private val _audioDelay = MutableStateFlow(0.0)
    val audioDelay: StateFlow<Double> = _audioDelay.asStateFlow()

    private val _subScale = MutableStateFlow(1.0)
    val subScale: StateFlow<Double> = _subScale.asStateFlow()

    private val _subPos = MutableStateFlow(100L)
    val subPos: StateFlow<Long> = _subPos.asStateFlow()

    // Shader Management (R1)
    private val _shaderState = MutableStateFlow(GlslShaderState())
    val shaderState: StateFlow<GlslShaderState> = _shaderState.asStateFlow()

    // Chapters (R3)
    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    private val _currentChapter = MutableStateFlow(-1)
    val currentChapter: StateFlow<Int> = _currentChapter.asStateFlow()

    // Volume Overdrive & Software Gain (R4)
    private val _softwareVolume = MutableStateFlow(100.0)
    val softwareVolume: StateFlow<Double> = _softwareVolume.asStateFlow()

    // Audiovisual Filters (R4)
    private val _deband = MutableStateFlow(false)
    val deband: StateFlow<Boolean> = _deband.asStateFlow()

    private val _brightness = MutableStateFlow(0)
    val brightness: StateFlow<Int> = _brightness.asStateFlow()

    private val _contrast = MutableStateFlow(0)
    val contrast: StateFlow<Int> = _contrast.asStateFlow()

    private val _saturation = MutableStateFlow(0)
    val saturation: StateFlow<Int> = _saturation.asStateFlow()

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode.asStateFlow()

    private val _stereoDownmix = MutableStateFlow(false)
    val stereoDownmix: StateFlow<Boolean> = _stereoDownmix.asStateFlow()

    @Volatile
    private var pendingSeekMs: Long = -1L
    @Volatile
    private var fileLoaded: Boolean = false

    private val nativeListener = object : MpvNativeBridge.EventListener {
        override fun onEvent(eventId: Int) {
            when (eventId) {
                MpvNativeBridge.Event.FILE_LOADED -> {
                    fileLoaded = true
                    val hwdec = bridge.getPropertyString("hwdec-current") ?: "none"
                    _hwdecCurrent.value = hwdec
                    if (requestedTitle.isBlank()) {
                        val title = bridge.getPropertyString("media-title")
                        if (!title.isNullOrBlank()) {
                            _mediaTitle.value = title
                        }
                    }
                    _playbackState.value = PlaybackState.READY
                    refreshTracks()
                    refreshChapters()
                    Log.i(TAG, "File loaded, active hwdec=$hwdec")

                    if (pendingSeekMs >= 0L) {
                        val target = pendingSeekMs
                        pendingSeekMs = -1L
                        seekTo(target)
                    }
                }
                MpvNativeBridge.Event.TRACKS_CHANGED -> {
                    refreshTracks()
                }
                MpvNativeBridge.Event.END_FILE -> {
                    fileLoaded = false
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.ENDED
                    _chapters.value = emptyList()
                    _currentChapter.value = -1
                    resetSoftwareGain()
                }
                MpvNativeBridge.Event.SHUTDOWN -> {
                    fileLoaded = false
                    _isPlaying.value = false
                    _playbackState.value = PlaybackState.IDLE
                    _chapters.value = emptyList()
                    _currentChapter.value = -1
                    resetSoftwareGain()
                }
                MpvNativeBridge.Event.PLAYBACK_RESTART -> {
                    _playbackState.value = PlaybackState.READY
                }
                MpvNativeBridge.Event.WATCHDOG_FALLBACK -> {
                    _hwdecCurrent.value = "mediacodec-copy (fallback)"
                    Log.w(TAG, "Watchdog triggered: GPU hang/timeout detected, safely fell back to mediacodec-copy")
                }
            }
        }

        override fun onProperty(name: String) {
            when (name) {
                "track-list" -> refreshTracks()
                "chapter-list" -> refreshChapters()
            }
        }

        override fun onPropertyLong(name: String, value: Long) {
            when (name) {
                "width" -> {
                    val h = bridge.getLong("height")?.toInt() ?: 0
                    if (value > 0 && h > 0) {
                        _videoDimensions.value = VideoDimensions(value.toInt(), h)
                    }
                }
                "height" -> {
                    val w = bridge.getLong("width")?.toInt() ?: 0
                    if (w > 0 && value > 0) {
                        _videoDimensions.value = VideoDimensions(w, value.toInt())
                    }
                }
                "frame-drop-count" -> {
                    _droppedFrames.value = value
                }
                "sub-pos" -> {
                    _subPos.value = value
                }
                "chapter" -> {
                    _currentChapter.value = value.toInt()
                }
                "brightness" -> {
                    _brightness.value = value.toInt()
                }
                "contrast" -> {
                    _contrast.value = value.toInt()
                }
                "saturation" -> {
                    _saturation.value = value.toInt()
                }
            }
        }

        override fun onPropertyDouble(name: String, value: Double) {
            when (name) {
                "time-pos" -> {
                    _timePosMs.value = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "duration" -> {
                    _durationMs.value = (value * 1000.0).toLong().coerceAtLeast(0L)
                }
                "demuxer-cache-time" -> {
                    _bufferedPosMs.value = (value * 1000.0).toLong().coerceAtLeast(_timePosMs.value)
                }
                "speed" -> {
                    _speed.value = value.toFloat()
                }
                "estimated-vf-fps" -> {
                    _fps.value = value
                }
                "sub-delay" -> {
                    _subDelay.value = value
                }
                "audio-delay" -> {
                    _audioDelay.value = value
                }
                "sub-scale" -> {
                    _subScale.value = value
                }
            }
        }

        override fun onPropertyFlag(name: String, value: Boolean) {
            when (name) {
                "pause" -> {
                    _isPlaying.value = !value
                    if (!value && _playbackState.value != PlaybackState.BUFFERING) {
                        _playbackState.value = PlaybackState.READY
                    }
                }
                "paused-for-cache" -> {
                    if (value) {
                        _playbackState.value = PlaybackState.BUFFERING
                    } else if (_isPlaying.value) {
                        _playbackState.value = PlaybackState.READY
                    }
                }
                "deband" -> {
                    _deband.value = value
                }
            }
        }

        override fun onPropertyString(name: String, value: String) {
            when (name) {
                "hwdec-current" -> _hwdecCurrent.value = value
                "media-title" -> if (value.isNotBlank() && requestedTitle.isBlank()) {
                    _mediaTitle.value = value
                }
            }
        }
    }

    fun ensureInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                bridge.create(appContext)

                // Pre-extract built-in shaders to POSIX filesDir
                shaderManager.extractBuiltInShaders()

                // Query static environment probe
                val isZeroCopySafe = bridge.isZeroCopySafe()
                val gpuRenderer = bridge.getGpuRenderer()

                // =================================================================
                // 1. INJECT USER mpv.conf (Excluding Protected Engine Keys)
                // =================================================================
                val userOptions = mpvConfigRepository?.getParsedOptions() ?: emptyMap()
                for ((key, value) in userOptions) {
                    val normalizedKey = key.trim().lowercase()
                    if (normalizedKey in PROTECTED_OPTION_KEYS) {
                        Log.w(TAG, "Ignoring protected mpv.conf key '$key' with value '$value'")
                        continue
                    }
                    val code = bridge.setOptionString(key, value)
                    if (code != 0) {
                        Log.w(TAG, "mpv rejected option '$key=$value' (code=$code)")
                    }
                }

                // =================================================================
                // 2. INJECT GUI EXPLICIT SETTINGS (Unconditionally Overrides mpv.conf)
                // =================================================================
                val userSettings = settingsRepository?.settings?.value
                val userHwdec = userSettings?.defaultHwdec?.trim() ?: ""
                val hwdecConfig = when {
                    userHwdec.isNotBlank() -> userHwdec
                    isZeroCopySafe -> "mediacodec,mediacodec-copy"
                    else -> "mediacodec-copy"
                }
                bridge.setOptionString("hwdec", hwdecConfig)
                bridge.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

                val subAuto = if (userSettings?.subAutoLoad != false) "all" else "no"
                bridge.setOptionString("sub-auto", subAuto)

                // Enforce engine lifecycle invariant contracts
                bridge.setOptionString("vo", "libmpv")
                bridge.setOptionString("idle", "yes")
                bridge.setOptionString("keep-open", "yes")
                bridge.setOptionString("ao", "audiotrack,opensles")
                bridge.setOptionString("volume-max", "200.0")

                // Provide sensible defaults if not set in mpv.conf
                if (!userOptions.containsKey("demuxer-max-bytes")) {
                    bridge.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
                }
                if (!userOptions.containsKey("demuxer-max-back-bytes")) {
                    bridge.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
                }
                if (!userOptions.containsKey("sub-font-provider")) {
                    bridge.setOptionString("sub-font-provider", "none")
                }
                if (!userOptions.containsKey("sub-ass-override")) {
                    bridge.setOptionString("sub-ass-override", "no")
                }
                if (!userOptions.containsKey("blend-subtitles")) {
                    bridge.setOptionString("blend-subtitles", "no")
                }

                // =================================================================
                // 3. INITIALIZE MPV CORE
                // =================================================================
                bridge.init()
                bridge.eventListener = nativeListener

                // Restore active shader if previously configured
                val currentShader = _shaderState.value
                val currentPath = currentShader.currentPath
                if (currentShader.activeShader != GlslShader.OFF && currentPath != null) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", currentPath))
                }

                // Register property observers
                bridge.observeProperty("time-pos", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("duration", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("pause", MpvNativeBridge.Format.FLAG)
                bridge.observeProperty("paused-for-cache", MpvNativeBridge.Format.FLAG)
                bridge.observeProperty("demuxer-cache-time", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("width", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("height", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("hwdec-current", MpvNativeBridge.Format.STRING)
                bridge.observeProperty("speed", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("estimated-vf-fps", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("frame-drop-count", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("track-list", MpvNativeBridge.Format.NONE)
                bridge.observeProperty("sub-delay", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("audio-delay", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("media-title", MpvNativeBridge.Format.STRING)
                bridge.observeProperty("sub-scale", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("sub-pos", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("chapter-list", MpvNativeBridge.Format.NONE)
                bridge.observeProperty("chapter", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("deband", MpvNativeBridge.Format.FLAG)
                bridge.observeProperty("brightness", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("contrast", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("saturation", MpvNativeBridge.Format.INT64)

                Log.i(TAG, "GyroPlayerEngine initialized successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize GyroPlayerEngine", t)
                isInitialized.set(false)
            }
        }
    }

    fun loadUri(uri: Uri, startPositionMs: Long = 0L) {
        resetSoftwareGain()
        val fastTitle = uri.lastPathSegment ?: ""
        if (fastTitle.isNotBlank()) {
            _mediaTitle.value = fastTitle
        }

        engineScope.launch(Dispatchers.IO) {
            val displayName = runCatching {
                appContext.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            }.getOrNull() ?: fastTitle
            if (displayName.isNotBlank()) {
                _mediaTitle.value = displayName
            }

            val targetPath = try {
                val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                val fd = pfd?.detachFd() ?: -1
                if (fd >= 0) {
                    "fdclose://$fd"
                } else {
                    uri.path ?: uri.toString()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "openFileDescriptor fallback for uri=$uri: ${e.message}")
                if (uri.scheme == "file") {
                    uri.path ?: uri.toString()
                } else {
                    uri.toString()
                }
            }
            Log.i(TAG, "loadUri: uri=$uri -> targetPath=$targetPath")
            withContext(Dispatchers.Main) {
                load(targetPath, startPositionMs, displayName)
            }
        }
    }

    @Volatile
    private var isSurfaceAttached = false
    @Volatile
    private var pendingLoadUrl: String? = null
    @Volatile
    private var pendingLoadStartMs: Long = 0L
    @Volatile
    private var pendingLoadTitle: String? = null

    fun load(url: String, startPositionMs: Long = 0L, displayTitle: String? = null) {
        Log.i(TAG, "load: url=$url, startPositionMs=$startPositionMs, surfaceAttached=$isSurfaceAttached")
        resetSoftwareGain()
        ensureInitialized()

        requestedTitle = displayTitle?.trim().orEmpty()
        val resolvedTitle = requestedTitle.ifBlank {
            url.substringAfterLast('/').substringAfterLast('\\')
                .takeIf { candidate -> candidate.isNotBlank() && !candidate.startsWith("fdclose://") }
                .orEmpty()
        }
        _mediaTitle.value = resolvedTitle
        // Keep mpv's own metadata in step: media-title feeds the notification and media session, so
        // a mismatch leaks the descriptor number outside the player screen.
        bridge.setPropertyString("force-media-title", resolvedTitle)

        if (!isSurfaceAttached) {
            Log.i(TAG, "load: SurfaceView 尚未挂载，先进入等待队列: $url")
            pendingLoadUrl = url
            pendingLoadStartMs = startPositionMs
            pendingLoadTitle = displayTitle
            _playbackState.value = PlaybackState.BUFFERING
            return
        }
        pendingLoadUrl = null
        fileLoaded = false
        pendingSeekMs = if (startPositionMs > 0L) startPositionMs else -1L
        _playbackState.value = PlaybackState.BUFFERING
        bridge.command(arrayOf("loadfile", url, "replace"))
        play()
    }

    /**
     * Starts or resumes playback, gated on audio focus.
     *
     * When the system defers the grant (mid-call, assistant active) this returns without touching
     * mpv; [GyroAudioFocusManager] starts playback as soon as the grant lands.
     */
    fun play() {
        if (!audioFocusManager.requestPlay()) {
            Log.i(TAG, "play(): playback suppressed until audio focus is granted")
            return
        }
        bridge.setPropertyBoolean("pause", false)
    }

    fun pause() {
        audioFocusManager.notifyUserPaused()
        bridge.setPropertyBoolean("pause", true)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (!fileLoaded) {
            pendingSeekMs = positionMs
            return
        }
        val seconds = positionMs / 1000.0
        bridge.command(arrayOf("seek", "$seconds", "absolute"))
    }

    fun seekRelative(offsetMs: Long) {
        if (!fileLoaded) return
        val seconds = offsetMs / 1000.0
        bridge.command(arrayOf("seek", "$seconds", "relative"))
    }

    fun setSpeed(speed: Float) {
        bridge.setPropertyDouble("speed", speed.toDouble().coerceIn(0.25, 4.0))
    }

    fun refreshTracks() {
        try {
            val count = bridge.getLong("track-list/count") ?: 0L
            val trackList = mutableListOf<TrackInfo>()
            for (i in 0 until count) {
                val id = bridge.getLong("track-list/$i/id")?.toInt() ?: (i.toInt() + 1)
                val type = bridge.getPropertyString("track-list/$i/type") ?: ""
                val title = bridge.getPropertyString("track-list/$i/title")
                val lang = bridge.getPropertyString("track-list/$i/lang")
                val selected = bridge.getBoolean("track-list/$i/selected") ?: false
                trackList.add(TrackInfo(id, type, title, lang, selected))
            }
            _tracks.value = trackList
            Log.i(TAG, "Refreshed tracks: count=$count, tracks=$trackList")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh tracks: ${t.message}")
        }
    }

    fun selectTrack(type: String, id: Int) {
        when (type) {
            "audio" -> {
                if (id <= 0) {
                    bridge.setPropertyString("aid", "no")
                } else {
                    bridge.setPropertyString("aid", "$id")
                }
            }
            "sub" -> {
                if (id <= 0) {
                    bridge.setPropertyString("sid", "no")
                } else {
                    bridge.setPropertyString("sid", "$id")
                }
            }
        }
        refreshTracks()
    }

    fun addSubtitle(path: String) {
        bridge.command(arrayOf("sub-add", path, "select"))
        refreshTracks()
    }

    fun setSubtitleDelay(delaySec: Double) {
        val clamped = (Math.round(delaySec * 10.0) / 10.0).coerceIn(-10.0, 10.0)
        _subDelay.value = clamped
        bridge.setPropertyDouble("sub-delay", clamped)
    }

    fun setAudioDelay(delaySec: Double) {
        val clamped = (Math.round(delaySec * 10.0) / 10.0).coerceIn(-5.0, 5.0)
        _audioDelay.value = clamped
        bridge.setPropertyDouble("audio-delay", clamped)
    }

    fun setSubtitleScale(scale: Double) {
        val clamped = (Math.round(scale * 100.0) / 100.0).coerceIn(0.5, 3.0)
        _subScale.value = clamped
        bridge.setPropertyDouble("sub-scale", clamped)
    }

    fun setSubtitlePosition(posPercent: Long) {
        val clamped = posPercent.coerceIn(0L, 100L)
        _subPos.value = clamped
        bridge.setPropertyLong("sub-pos", clamped)
    }

    fun toggleHwdec() {
        val current = _hwdecCurrent.value
        val isHw = current.contains("mediacodec")
        val target = if (isHw) "no" else (if (bridge.isZeroCopySafe()) "mediacodec,mediacodec-copy" else "mediacodec-copy")
        bridge.setPropertyString("hwdec", target)
        _hwdecCurrent.value = target
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

    fun setVideoZoomAndPan(zoom: Double, panX: Double, panY: Double) {
        bridge.setPropertyDouble("video-zoom", zoom)
        bridge.setPropertyDouble("video-pan-x", panX)
        bridge.setPropertyDouble("video-pan-y", panY)
    }

    fun resetVideoZoomAndPan() {
        bridge.setPropertyDouble("video-zoom", 0.0)
        bridge.setPropertyDouble("video-pan-x", 0.0)
        bridge.setPropertyDouble("video-pan-y", 0.0)
    }

    fun attachSurface(surface: Surface) {
        isSurfaceAttached = true
        bridge.attachSurface(surface)
        pendingLoadUrl?.let { url ->
            Log.i(TAG, "attachSurface: Surface 已就绪，触发排队的待播视频: $url")
            val title = pendingLoadTitle
            pendingLoadTitle = null
            load(url, pendingLoadStartMs, title)
        }
    }

    fun detachSurface() {
        if (!isSurfaceAttached) return
        isSurfaceAttached = false
        bridge.detachSurface()
    }

    fun redraw() {
        if (isSurfaceAttached) {
            bridge.redraw()
        }
    }

    fun queryMediaInfo(fallbackTitle: String = "", fallbackUri: String = "", fallbackSize: Long = 0L): MediaInfo {
        val title = _mediaTitle.value.ifBlank { fallbackTitle }
        val dims = _videoDimensions.value
        val resStr = if (dims.width > 0 && dims.height > 0) "${dims.width} × ${dims.height}" else ""
        val format = bridge.getPropertyString("file-format") ?: ""
        val vCodec = bridge.getPropertyString("video-codec") ?: bridge.getPropertyString("video-format") ?: ""
        val vBitrate = bridge.getLong("video-bitrate") ?: 0L
        val aCodec = bridge.getPropertyString("audio-codec-name") ?: bridge.getPropertyString("audio-format") ?: ""
        val aChannels = bridge.getPropertyString("audio-params/channels") ?: ""
        val aSampleRate = bridge.getLong("audio-params/samplerate") ?: 0L
        val aBitrate = bridge.getLong("audio-bitrate") ?: 0L
        val size = bridge.getLong("file-size")?.takeIf { it > 0 } ?: fallbackSize

        return MediaInfo(
            title = title,
            pathOrUri = fallbackUri,
            containerFormat = format,
            fileSize = size,
            durationMs = _durationMs.value,
            videoCodec = vCodec,
            videoResolution = resStr,
            videoFps = _fps.value,
            videoBitrate = vBitrate,
            audioCodec = aCodec,
            audioChannels = aChannels,
            audioSampleRate = aSampleRate,
            audioBitrate = aBitrate,
            hwdecCurrent = _hwdecCurrent.value,
            droppedFrames = _droppedFrames.value
        )
    }

    // =========================================================================
    // Shader Control Methods (R1)
    // =========================================================================

    /**
     * Hot-swaps the active GLSL shader profile.
     */
    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null) {
        ensureInitialized()
        when (shader) {
            GlslShader.OFF -> {
                clearShader()
            }
            GlslShader.CAS -> {
                val file = shaderManager.getBuiltInShaderFile(GlslShader.CAS)
                if (file != null && file.exists()) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", file.absolutePath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.CAS,
                        currentPath = file.absolutePath,
                        displayName = "AMD FidelityFX CAS"
                    )
                    Log.i(TAG, "Applied CAS shader: ${file.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to apply CAS shader: file missing")
                }
            }
            GlslShader.ANIME4K -> {
                val file = shaderManager.getBuiltInShaderFile(GlslShader.ANIME4K)
                if (file != null && file.exists()) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", file.absolutePath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.ANIME4K,
                        currentPath = file.absolutePath,
                        displayName = "Anime4K Lite"
                    )
                    Log.i(TAG, "Applied Anime4K Lite shader: ${file.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to apply Anime4K Lite shader: file missing")
                }
            }
            GlslShader.CUSTOM -> {
                if (!customPath.isNullOrBlank() && File(customPath).exists()) {
                    val displayName = customName ?: File(customPath).nameWithoutExtension
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", customPath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.CUSTOM,
                        currentPath = customPath,
                        displayName = displayName
                    )
                    Log.i(TAG, "Applied custom shader: $customPath ($displayName)")
                } else {
                    Log.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
                }
            }
        }
    }

    /**
     * Clears all active shaders.
     */
    fun clearShader() {
        ensureInitialized()
        bridge.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        _shaderState.value = GlslShaderState(
            activeShader = GlslShader.OFF,
            currentPath = null,
            displayName = null
        )
        Log.i(TAG, "Cleared glsl-shaders")
    }

    /**
     * Convenience method to cache and apply a user-selected SAF custom shader.
     */
    fun importAndSetCustomShader(uri: Uri): Result<String> {
        return shaderManager.importCustomShader(uri).map { file ->
            val name = file.nameWithoutExtension
            setShader(GlslShader.CUSTOM, customPath = file.absolutePath, customName = name)
            file.absolutePath
        }
    }

    /**
     * Returns list of currently cached custom shaders.
     */
    fun getCustomShaders(): List<File> = shaderManager.listCustomShaders()

    // =========================================================================
    // Chapter Methods (R3)
    // =========================================================================

    fun refreshChapters() {
        try {
            val count = bridge.getLong("chapter-list/count") ?: 0L
            if (count <= 0L) {
                _chapters.value = emptyList()
                _currentChapter.value = -1
                return
            }

            val chapterList = ArrayList<ChapterInfo>(count.toInt())
            for (i in 0 until count) {
                val rawTitle = bridge.getPropertyString("chapter-list/$i/title")?.trim()
                val timeSec = bridge.getDouble("chapter-list/$i/time") ?: 0.0
                val timeMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L)
                val title = if (!rawTitle.isNullOrBlank()) rawTitle else "Chapter ${i + 1}"
                chapterList.add(ChapterInfo(index = i.toInt(), title = title, timeMs = timeMs))
            }
            _chapters.value = chapterList

            val activeChapter = bridge.getLong("chapter")?.toInt() ?: -1
            _currentChapter.value = activeChapter
            Log.i(TAG, "Refreshed chapters: count=$count, activeChapter=$activeChapter")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh chapters: ${t.message}")
        }
    }

    fun seekToChapter(index: Int) {
        val chapterList = _chapters.value
        if (index !in chapterList.indices) {
            Log.w(TAG, "seekToChapter: index $index out of bounds (0..${chapterList.size - 1})")
            return
        }

        if (!fileLoaded) {
            pendingSeekMs = chapterList[index].timeMs
            return
        }

        bridge.setPropertyLong("chapter", index.toLong())
        _currentChapter.value = index
        Log.i(TAG, "seekToChapter: jumped to chapter $index (${chapterList[index].title})")
    }

    // =========================================================================
    // Volume Overdrive & Software Gain Methods (R4)
    // =========================================================================

    @Synchronized
    fun setSoftwareVolume(gain: Double) {
        val clamped = gain.coerceIn(100.0, 200.0)
        _softwareVolume.value = clamped
        bridge.setPropertyDouble("volume", clamped)
        updateAudioFilters()
        Log.d(TAG, "setSoftwareVolume: gain=$clamped, limiterActive=${clamped > 100.0}")
    }

    @Synchronized
    fun resetSoftwareGain() {
        _softwareVolume.value = 100.0
        bridge.setPropertyDouble("volume", 100.0)
        updateAudioFilters()
        Log.d(TAG, "resetSoftwareGain: software gain reset to 100.0% (limiter cleared)")
    }

    // =========================================================================
    // Visual AV Filter Methods (R4)
    // =========================================================================

    fun setDeband(enabled: Boolean) {
        _deband.value = enabled
        bridge.setPropertyBoolean("deband", enabled)
    }

    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _brightness.value = clamped
        bridge.setPropertyLong("brightness", clamped.toLong())
    }

    fun setContrast(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _contrast.value = clamped
        bridge.setPropertyLong("contrast", clamped.toLong())
    }

    fun setSaturation(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _saturation.value = clamped
        bridge.setPropertyLong("saturation", clamped.toLong())
    }

    fun resetColors() {
        setBrightness(0)
        setContrast(0)
        setSaturation(0)
    }

    // =========================================================================
    // Dialogue Enhancement, Stereo Downmix & Audio Filter Pipeline (R4)
    // =========================================================================

    @Synchronized
    fun setNightMode(enabled: Boolean) {
        if (_nightMode.value == enabled) return
        _nightMode.value = enabled
        updateAudioFilters()
    }

    @Synchronized
    fun setStereoDownmix(enabled: Boolean) {
        if (_stereoDownmix.value == enabled) return
        _stereoDownmix.value = enabled
        updateAudioFilters()
    }

    @Synchronized
    private fun updateAudioFilters() {
        val activeFilters = ArrayList<String>(3)

        // Pipeline Stage 1: Downmixing (pan)
        if (_stereoDownmix.value) {
            activeFilters.add(FILTER_STEREO_DOWNMIX)
        }

        // Pipeline Stage 2: Dynamic Range Normalization (dynaudnorm)
        if (_nightMode.value) {
            activeFilters.add(FILTER_DYNAUDNORM)
        }

        // Pipeline Stage 3: Brickwall Anti-Clipping Limiter (alimiter)
        if (_softwareVolume.value > 100.0) {
            activeFilters.add(FILTER_ALIMITER)
        }

        val afChain = activeFilters.joinToString(",")
        bridge.setPropertyString("af", afChain)
        Log.d(TAG, "Active audio filter chain: '$afChain'")
    }

    fun release() {
        engineScope.cancel()
        audioFocusManager.abandon()
        resetSoftwareGain()
        isInitialized.set(false)
        bridge.eventListener = null
        bridge.destroy()
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
    }
}
