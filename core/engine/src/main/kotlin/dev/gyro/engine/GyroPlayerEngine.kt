package dev.gyro.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.UserSettings
import dev.gyro.engine.audio.GyroAudioFocusManager
import dev.gyro.engine.config.MpvOptionConfigurator
import dev.gyro.engine.effects.AudioEffectsController
import dev.gyro.engine.effects.RealAudioEffectsController
import dev.gyro.engine.effects.RealVideoEffectsController
import dev.gyro.engine.effects.VideoEffectsController
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.media.ResolvedMediaSource
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.session.PlaybackTimelineController
import dev.gyro.engine.session.RealPlaybackTimelineController
import dev.gyro.engine.session.RealVideoGeometryCoordinator
import dev.gyro.engine.session.VideoGeometryCoordinator
import dev.gyro.engine.shader.RealShaderController
import dev.gyro.engine.shader.ShaderController
import dev.gyro.engine.shader.ShaderManager
import dev.gyro.engine.snapshot.CorePlaybackInputs
import dev.gyro.engine.snapshot.PlaybackSnapshotAggregator
import dev.gyro.engine.trace.SwitchTrace
import dev.gyro.engine.tracks.RealTrackAndChapterManager
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.tuning.RealSyncTuningController
import dev.gyro.engine.tuning.SyncTuningController
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class GyroPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: MpvNativeBridge = RealMpvNativeBridge,
    private val resolver: MediaResourceResolver = DefaultMediaResourceResolver(context),
    private val configurator: MpvOptionConfigurator = MpvOptionConfigurator(),
    val shaders: ShaderController = RealShaderController(bridge, ShaderManager(context)),
    val geometry: VideoGeometryCoordinator = RealVideoGeometryCoordinator(bridge),
    val audio: AudioEffectsController = RealAudioEffectsController(bridge),
    val video: VideoEffectsController = RealVideoEffectsController(bridge),
    val trackManager: TrackAndChapterManager = RealTrackAndChapterManager(bridge),
    val tuning: SyncTuningController = RealSyncTuningController(bridge),
    private val timeline: PlaybackTimelineController = RealPlaybackTimelineController(bridge, geometry, trackManager)
) {

    companion object {
        private const val TAG = "GyroPlayerEngine"
    }

    private val isInitialized = AtomicBoolean(false)
    private var engineJob = SupervisorJob()
    private var engineScope = CoroutineScope(engineJob + Dispatchers.Default)

    /**
     * Audio focus lives here rather than in the ViewModel or the service so that the same state
     * machine covers foreground playback, background audio and media-button resumes.
     */
    private val audioFocusManager = GyroAudioFocusManager(
        context = context,
        listener = object : GyroAudioFocusManager.Listener {
            override fun onPlayRequested() {
                bridge.setPropertyBoolean("pause", false)
            }

            override fun onPauseRequested() {
                bridge.setPropertyBoolean("pause", true)
            }

            override val isPlaying: Boolean
                get() = _isPlaying.value
        }
    )

    // Playback Flow states
    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    val timePosMs: StateFlow<Long>
        get() = timeline.timePosMs

    val durationMs: StateFlow<Long>
        get() = timeline.durationMs

    val bufferedPosMs: StateFlow<Long>
        get() = timeline.bufferedPosMs

    private val _mediaTitle = MutableStateFlow("")
    val mediaTitle: StateFlow<String> = _mediaTitle.asStateFlow()

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri.asStateFlow()

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

    private val _fps = MutableStateFlow(0.0)
    val fps: StateFlow<Double> = _fps.asStateFlow()

    private val _droppedFrames = MutableStateFlow(0L)
    val droppedFrames: StateFlow<Long> = _droppedFrames.asStateFlow()

    val isFileLoaded: Boolean
        get() = timeline.isFileLoaded

    private val snapshotAggregator = PlaybackSnapshotAggregator(
        core = CorePlaybackInputs(
            playbackState = _playbackState,
            isPlaying = _isPlaying,
            currentUri = _currentUri,
            mediaTitle = _mediaTitle,
            durationMs = timeline.durationMs,
            hwdecCurrent = _hwdecCurrent,
            speed = _speed,
            fps = _fps,
            droppedFrames = _droppedFrames
        ),
        geometry = geometry,
        audio = audio,
        video = video,
        tracks = trackManager,
        tuning = tuning,
        shaders = shaders
    )

    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    /**
     * Every slow-moving playback fact in one consistent object. Read this instead of collecting the
     * individual flows: one subscription, and no pair of fields from two different events.
     * Position/buffered stay on their own flows, see [PlaybackSnapshot].
     */
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var snapshotJob: Job? = null

    private fun startSnapshotAggregation() {
        snapshotJob?.cancel()
        // Unconfined + UNDISPATCHED keeps the snapshot current on the thread that changed a source,
        // so a caller that reads `snapshot` right after a command sees its own write.
        snapshotJob = engineScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            snapshotAggregator.flow().collect { _snapshot.value = it }
        }
    }

    private fun observePlaybackEvents() = engineScope.launch(
        Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED
    ) {
        bridge.events.collect { event ->
            when (event) {
                is MpvEvent.Lifecycle -> when (event.id) {
                    MpvNativeBridge.Event.FIRST_FRAME -> {
                        SwitchTrace.log("KOTLIN_EVENT_FIRST_FRAME", "timePos=${timePosMs.value}ms")
                        Log.i(TAG, "First frame presented at ${timePosMs.value}ms")
                    }
                    MpvNativeBridge.Event.FILE_LOADED -> {
                        SwitchTrace.log("KOTLIN_EVENT_FILE_LOADED", "")
                        _hwdecCurrent.value = bridge.getPropertyString("hwdec-current") ?: "none"
                        if (requestedTitle.isBlank()) {
                            bridge.getPropertyString("media-title")?.takeIf { it.isNotBlank() }?.let {
                                _mediaTitle.value = it
                            }
                        }
                        _playbackState.value = PlaybackState.READY
                        // Consecutive unpaused files need not produce another pause property event.
                        bridge.getBoolean("pause")?.let { _isPlaying.value = !it }
                    }
                    MpvNativeBridge.Event.END_FILE -> {
                        _isPlaying.value = false
                        _playbackState.value = PlaybackState.ENDED
                    }
                    MpvNativeBridge.Event.SHUTDOWN -> {
                        _isPlaying.value = false
                        _playbackState.value = PlaybackState.IDLE
                    }
                    MpvNativeBridge.Event.PLAYBACK_RESTART -> _playbackState.value = PlaybackState.READY
                }
                is MpvEvent.LongProperty -> when (event.name) {
                    "frame-drop-count" -> _droppedFrames.value = event.value
                }
                is MpvEvent.DoubleProperty -> when (event.name) {
                    "speed" -> _speed.value = event.value.toFloat()
                    "estimated-vf-fps" -> _fps.value = event.value
                }
                is MpvEvent.FlagProperty -> when (event.name) {
                    "pause" -> {
                        _isPlaying.value = !event.value
                        if (!event.value && _playbackState.value != PlaybackState.BUFFERING) {
                            _playbackState.value = PlaybackState.READY
                        }
                    }
                    "paused-for-cache" -> {
                        if (event.value) {
                            _playbackState.value = PlaybackState.BUFFERING
                        } else if (_isPlaying.value) {
                            _playbackState.value = PlaybackState.READY
                        }
                    }
                }
                is MpvEvent.StringProperty -> when (event.name) {
                    "hwdec-current" -> _hwdecCurrent.value = event.value
                    "media-title" -> if (event.value.isNotBlank() && requestedTitle.isBlank()) {
                        _mediaTitle.value = event.value
                    }
                }
                is MpvEvent.PropertyChanged -> Unit
            }
        }
    }

    /**
     * Initialises the native core.
     *
     * Configuration arrives as values, not as repositories: the engine must not know where
     * settings live. The caller reads them and hands over a snapshot, which also keeps this
     * method synchronous — mpv requires every option to be set before `mpv_init()`.
     *
     * @param userOptions parsed `mpv.conf` entries; protected keys are dropped here.
     * @param settings GUI settings, applied after [userOptions] so they win.
     */
    fun ensureInitialized(
        userOptions: Map<String, String> = emptyMap(),
        settings: UserSettings = UserSettings(),
    ) {
        if (isInitialized.compareAndSet(false, true)) {
            try {
                if (!engineJob.isActive) {
                    engineJob = SupervisorJob()
                    engineScope = CoroutineScope(engineJob + Dispatchers.Default)
                }
                audioFocusManager.reset()
                bridge.create(context)

                // Pre-extract built-in shaders to POSIX filesDir
                shaders.extractBuiltInShaders()

                // mpv only accepts options before init(); ordering lives in the configurator.
                configurator.apply(bridge, userOptions, settings)

                // =================================================================
                // 3. INITIALIZE MPV CORE
                // =================================================================
                audio.observeEvents(engineScope)
                video.observeEvents(engineScope)
                trackManager.observeEvents(engineScope)
                geometry.observeEvents(engineScope)
                timeline.observeEvents(engineScope)
                tuning.observeEvents(engineScope)
                observePlaybackEvents()
                startSnapshotAggregation()
                bridge.init()

                // Seed display refresh rate if available from the display subsystem
                val initialDisplayFps = displayRefreshRate()
                if (initialDisplayFps != null && initialDisplayFps in 20f..300f) {
                    geometry.updateDisplayRefreshRate(initialDisplayFps)
                    // Applied explicitly as well: a surface callback may have measured this rate
                    // before the core existed, where the property write was dropped, and the
                    // dedupe in the coordinator would skip the re-apply.
                    bridge.setPropertyDouble("display-fps-override", initialDisplayFps.toDouble())
                    bridge.setPropertyDouble("display-fps", initialDisplayFps.toDouble())
                    Log.i(TAG, "Initialized display-fps-override -> $initialDisplayFps")
                }

                // Restore active shader if previously configured
                shaders.restoreActiveShader()

                // Register property observers
                bridge.observeProperty("time-pos", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("duration", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("pause", MpvNativeBridge.Format.FLAG)
                bridge.observeProperty("paused-for-cache", MpvNativeBridge.Format.FLAG)
                bridge.observeProperty("demuxer-cache-time", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("dwidth", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("dheight", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("width", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("height", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("hwdec-current", MpvNativeBridge.Format.STRING)
                bridge.observeProperty("speed", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("container-fps", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("estimated-vf-fps", MpvNativeBridge.Format.DOUBLE)
                bridge.observeProperty("frame-drop-count", MpvNativeBridge.Format.INT64)
                bridge.observeProperty("video-params", MpvNativeBridge.Format.NONE)
                bridge.observeProperty("video-out-params", MpvNativeBridge.Format.NONE)
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
                engineJob.cancel()
                isInitialized.set(false)
            }
        }
    }

    /** Refresh rate of the display the player is on, or null when it cannot be read. */
    private fun displayRefreshRate(): Float? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val rate = display.mode?.refreshRate ?: return null
        return rate.takeIf { it in 20f..300f }
    }

    @Volatile
    private var currentLoadJob: kotlinx.coroutines.Job? = null

    fun loadUri(uri: Uri, startPositionMs: Long = 0L, displayTitle: String? = null) =
        load(MediaItem(uri = uri, title = displayTitle, startPositionMs = startPositionMs))

    /**
     * Resolves [item] off the main thread, then hands the resulting source to mpv.
     *
     * The descriptor's ownership moves with the source: if this load is cancelled or replaced
     * before `loadfile` runs, the pending source is closed instead of leaking the fd. Resolution
     * failures surface as [PlaybackState.ERROR] rather than a silently black shutter.
     */
    fun load(item: MediaItem) {
        beginLoad(
            target = item.uri.toString(),
            title = item.title,
            startPositionMs = item.startPositionMs,
            uri = item.uri
        )

        currentLoadJob?.cancel()
        discardPending()
        currentLoadJob = engineScope.launch {
            var owned: ResolvedMediaSource? = null
            try {
                val resolved = resolver.resolve(item)
                owned = resolved
                SwitchTrace.log("RESOLVE_PATH_DONE", "target=${resolved.targetDescription}")
                withContext(Dispatchers.Main) {
                    submitOrQueue(resolved, item.startPositionMs, item.uri.toString())
                }
                // submitOrQueue owns the source from here: either queued or handed to mpv.
                owned = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "load: cannot resolve ${item.uri}", t)
                _playbackState.value = PlaybackState.ERROR
            } finally {
                owned?.close()
            }
        }
    }

    /**
     * Arms the shutter, publishes the title and initialises the core for one request.
     *
     * Runs synchronously on the caller's thread so the UI never paints the previous file's title or
     * position while the descriptor is still being resolved.
     */
    private fun beginLoad(target: String, title: String?, startPositionMs: Long, uri: Uri?) {
        SwitchTrace.start("LOAD_URI", "uri=$target, startPos=$startPositionMs")
        if (uri != null) _currentUri.value = uri
        geometry.onSwitchStarted(target, startPositionMs)
        audio.resetSoftwareGain()
        _playbackState.value = PlaybackState.BUFFERING
        SwitchTrace.log("SHUTTER_ARMED", "firstFrame=false, geometryReady=false")

        ensureInitialized()
        timeline.onLoadStarted(startPositionMs)

        requestedTitle = title?.trim().orEmpty()
        val resolvedTitle = requestedTitle.ifBlank {
            (uri?.lastPathSegment ?: target.substringAfterLast('/').substringAfterLast('\\'))
                .takeIf { candidate -> candidate.isNotBlank() && !candidate.startsWith("fdclose://") }
                .orEmpty()
        }
        _mediaTitle.value = resolvedTitle
        // Keep mpv's own metadata in step: media-title feeds the notification and media session, so
        // a mismatch leaks the descriptor number outside the player screen.
        bridge.setPropertyString("force-media-title", resolvedTitle)
    }

    /** True while the native renderer holds a bound surface; false means nothing is painting. */
    @Volatile
    var isSurfaceAttached = false
        private set

    @Volatile
    private var pendingSource: ResolvedMediaSource? = null
    @Volatile
    private var pendingStartMs: Long = 0L
    @Volatile
    private var pendingLabel: String = ""

    private fun discardPending() {
        pendingSource?.close()
        pendingSource = null
        pendingLabel = ""
    }

    /**
     * Direct-target entry used by tests and by callers that already hold a path; no descriptor
     * ownership is involved, so it stays synchronous.
     */
    fun load(url: String, startPositionMs: Long = 0L, displayTitle: String? = null) {
        beginLoad(target = url, title = displayTitle, startPositionMs = startPositionMs, uri = null)
        currentLoadJob?.cancel()
        discardPending()
        submitOrQueue(ResolvedMediaSource(url), startPositionMs, url)
    }

    private fun submitOrQueue(source: ResolvedMediaSource, startPositionMs: Long, label: String) {
        if (!isSurfaceAttached) {
            Log.i(TAG, "load: surface not attached yet, queueing $label")
            discardPending()
            pendingSource = source
            pendingStartMs = startPositionMs
            pendingLabel = label
            _playbackState.value = PlaybackState.BUFFERING
            return
        }
        submit(source, startPositionMs, label)
    }

    private fun submit(source: ResolvedMediaSource, startPositionMs: Long, label: String) {
        val target = source.takeTargetUrl()
        SwitchTrace.log("LOAD_COMMAND_ISSUED", "url=$target, isSurfaceAttached=$isSurfaceAttached")
        Log.i(TAG, "submit: url=$target, startPositionMs=$startPositionMs")
        timeline.onLoadCommandIssued()
        _playbackState.value = PlaybackState.BUFFERING
        // Resume positions ride the loadfile itself. Passing start= makes mpv decode its first
        // picture at the target timestamp; the old two-step (load from zero, then seek on
        // FILE_LOADED) painted frame zero for a beat before jumping, which flashed the wrong
        // scene on every resume.
        val startSeconds = startPositionMs / 1000.0
        val loadArgs = if (startPositionMs > 0L) {
            // libmpv's loadfile is <url> <flags> <index> <options>: the integer index must be
            // filled in before the options, or the whole command is rejected (rc=-4).
            arrayOf("loadfile", target, "replace", "0", "start=+${"%.3f".format(startSeconds)}")
        } else {
            arrayOf("loadfile", target, "replace")
        }
        bridge.command(loadArgs)
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

    fun seekTo(positionMs: Long) = timeline.seekTo(positionMs)

    fun seekRelative(offsetMs: Long) = timeline.seekRelative(offsetMs)

    fun setSpeed(speed: Float) {
        bridge.setPropertyDouble("speed", speed.toDouble().coerceIn(0.25, 4.0))
    }

    fun toggleHwdec() {
        val current = _hwdecCurrent.value
        val isHw = current.contains("mediacodec")
        val target = if (isHw) "no" else (if (bridge.isZeroCopySafe()) "mediacodec,mediacodec-copy" else "mediacodec-copy")
        bridge.setPropertyString("hwdec", target)
        _hwdecCurrent.value = target
    }

    private var attachedSurfaceRef: Surface? = null

    fun attachSurface(surface: Surface) {
        attachedSurfaceRef = surface
        isSurfaceAttached = true
        bridge.attachSurface(surface)
        pendingSource?.let { source ->
            val startMs = pendingStartMs
            val label = pendingLabel
            pendingSource = null
            pendingLabel = ""
            Log.i(TAG, "attachSurface: surface ready, flushing queued $label")
            submit(source, startMs, label)
        }
    }

    fun detachSurface(surface: Surface? = null) {
        if (!isSurfaceAttached) return
        // During an aspect-change rebuild two SurfaceView instances are alive for a few frames,
        // and the OLD one's destroy callback can land after the NEW one has attached: measured on
        // device, that teardown removed the fresh surface and the picture froze on it. Only the
        // surface the engine actually holds may unbind it.
        if (surface != null && attachedSurfaceRef !== surface) {
            Log.i(TAG, "detachSurface ignored: engine holds a different surface")
            return
        }
        isSurfaceAttached = false
        attachedSurfaceRef = null
        bridge.detachSurface()
    }

    fun redraw() {
        SwitchTrace.log("REDRAW_REQUESTED", "surfaceAttached=$isSurfaceAttached")
        if (isSurfaceAttached) {
            bridge.redraw()
        }
    }

    fun queryMediaInfo(fallbackTitle: String = "", fallbackUri: String = "", fallbackSize: Long = 0L): MediaInfo {
        val title = _mediaTitle.value.ifBlank { fallbackTitle }
        val dims = geometry.videoDimensions.value
        val resStr = if (dims.width > 0 && dims.height > 0) "${dims.width} x ${dims.height}" else ""
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
            durationMs = durationMs.value,
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
    // Chapter Methods (R3)
    // =========================================================================

    fun seekToChapter(index: Int) = timeline.seekToChapter(index)

    fun abandonAudioFocus() {
        audioFocusManager.abandon()
    }

    fun release() {
        engineJob.cancel()
        currentLoadJob?.cancel()
        discardPending()
        audioFocusManager.release()
        audio.resetSoftwareGain()
        trackManager.clearChapters()
        isInitialized.set(false)
        bridge.destroy()
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _currentUri.value = null
        geometry.reset()
    }
}
