package dev.gyro.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import android.view.Surface
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.UserSettings
import dev.gyro.engine.audio.AudioEffectsController
import dev.gyro.engine.audio.AudioFocusController
import dev.gyro.engine.audio.GyroAudioFocusManager
import dev.gyro.engine.config.MpvOptionConfigurator
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.media.MediaInfoExtractor
import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.session.MediaLoadCoordinator
import dev.gyro.engine.session.PlaybackLifecycleController
import dev.gyro.engine.session.PlaybackTimelineController
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.tuning.SyncTuningController
import dev.gyro.engine.video.ShaderController
import dev.gyro.engine.video.ShaderManager
import dev.gyro.engine.video.VideoEffectsController
import dev.gyro.engine.video.VideoGeometryCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

typealias MpvLoadException = dev.gyro.engine.session.MpvLoadException

/**
 * Unified playback engine facade.
 * Coordinates domain subsystems, manages native lifecycle, and exposes reactive playback state.
 */
class GyroPlayerEngine(
    private val context: Context? = null,
    private val bridge: MpvNativeBridge = RealMpvNativeBridge,
    private val resolver: MediaResourceResolver = if (context != null) DefaultMediaResourceResolver(context) else MediaResourceResolver.Direct,
    private val configurator: MpvOptionConfigurator = MpvOptionConfigurator(),
    internal val store: PlaybackStore = PlaybackStore(),
    override val playlist: dev.gyro.engine.media.PlaylistManager = dev.gyro.engine.media.PlaylistManager(),
    override val shaders: ShaderController = ShaderController(bridge, ShaderManager(context), store),
    override val geometry: VideoGeometryCoordinator = VideoGeometryCoordinator(bridge, store),
    override val audio: AudioEffectsController = AudioEffectsController(bridge, store),
    override val video: VideoEffectsController = VideoEffectsController(bridge, store),
    override val trackManager: TrackAndChapterManager = TrackAndChapterManager(bridge, store),
    override val tuning: SyncTuningController = SyncTuningController(bridge, store),
    val abLoop: dev.gyro.engine.session.AbLoopController = dev.gyro.engine.session.AbLoopController(bridge, store),
    internal val timeline: PlaybackTimelineController =
        PlaybackTimelineController(bridge, geometry, trackManager, store = store),
    audioFocus: AudioFocusController? = null,
    private val displayRefreshRateProvider: () -> Float? = { defaultDisplayRefreshRate(context) }
) : dev.gyro.engine.api.PlayerEngine {

    companion object {
        private fun defaultDisplayRefreshRate(context: Context?): Float? = runCatching {
            val ctx = context ?: return null
            val manager = ctx.getSystemService(DisplayManager::class.java) ?: return null
            val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val rate = display.mode?.refreshRate ?: return null
            rate.takeIf { it in 20f..300f }
        }.getOrNull()
    }

    private val isInitialized = AtomicBoolean(false)
    private var engineJob = SupervisorJob()
    private var engineScope = CoroutineScope(engineJob + Dispatchers.Default)

    internal val playback: PlaybackLifecycleController = PlaybackLifecycleController(
        bridge = bridge,
        store = store,
        audioFocusManager = audioFocus ?: if (context != null) {
            GyroAudioFocusManager(
                context = context,
                listener = object : GyroAudioFocusManager.Listener {
                    override fun onPlayRequested() {
                        bridge.setPropertyBoolean("pause", false)
                    }

                    override fun onPauseRequested() {
                        bridge.setPropertyBoolean("pause", true)
                    }

                    override val isPlaying: Boolean
                        get() = store.current.isPlaying
                }
            )
        } else {
            AudioFocusController.NoOp
        },
        getTimePosMs = { timeline.timePosMs.value },
        getRequestedTitle = { loadCoordinator.requestedTitle },
        onReplay = { timeline.seekTo(0L) }
    )

    internal val loadCoordinator: MediaLoadCoordinator = MediaLoadCoordinator(
        bridge = bridge,
        store = store,
        resolver = resolver,
        timeline = timeline,
        geometry = geometry,
        audio = audio,
        tuning = tuning,
        playbackController = playback,
        coroutineScope = { engineScope },
        ensureInitialized = { ensureNativeInitialized() }
    )

    val playlistItems: StateFlow<List<dev.gyro.core.model.VideoMediaItem>> get() = playlist.items
    val currentPlaylistIndex: StateFlow<Int> get() = playlist.currentIndex
    val currentMediaItem: dev.gyro.core.model.VideoMediaItem? get() = playlist.currentItem

    /**
     * Managed domain subsystems: observers, event dispatch, and state reset are orchestrated uniformly.
     */
    private val subsystems: List<EngineSubsystem> by lazy {
        listOf(geometry, trackManager, timeline, audio, video, tuning, shaders, abLoop, playback)
    }

    // =========================================================================
    // Reactive State Projections (Single source of truth: store.state)
    // =========================================================================

    override val state: StateFlow<PlaybackSnapshot> get() = store.state
    override val playbackState: StateFlow<PlaybackState> = store.select { it.playbackState }
    override val isPlaying: StateFlow<Boolean> = store.select { it.isPlaying }
    override val currentUri: StateFlow<Uri?> = store.select { it.currentUri }
    val mediaTitle: StateFlow<String> = store.select { it.mediaTitle }
    val speed: StateFlow<Float> = store.select { it.speed }
    override val timePosMs: StateFlow<Long> get() = timeline.timePosMs
    val durationMs: StateFlow<Long> get() = timeline.durationMs
    override val bufferedPosMs: StateFlow<Long> get() = timeline.bufferedPosMs
    val isFileLoaded: Boolean get() = timeline.isFileLoaded
    override val isSurfaceAttached: Boolean get() = loadCoordinator.isSurfaceAttached

    override fun addSubtitle(path: String) = trackManager.addSubtitle(path)

    // =========================================================================
    // Core Engine Lifecycle & Native Initialization
    // =========================================================================

    /**
     * Declares configuration (options and settings).
     * If native engine is not started yet, updates configuration memory and applies them when
     * playback or surface starts. If native engine is already started, applies runtime updates.
     */
    fun configure(
        userOptions: Map<String, String> = configurator.currentState.userOptions,
        settings: UserSettings = configurator.currentState.settings,
    ) {
        store.update {
            it.copy(
                hdrOutputMode = settings.hdrOutputMode,
                toneMappingAlgorithm = settings.toneMappingAlgorithm,
                videoScale = settings.videoScale,
                interpolation = settings.interpolation,
                audioPassthrough = settings.audioPassthrough
            )
        }
        userOptions["af"]?.let { audio.userBaseAf = it }
        if (isInitialized.get()) {
            configurator.applyRuntimeUpdate(bridge, settings, userOptions)
            geometry.onHdrOutputModeChanged()
            geometry.setToneMappingAlgorithm(settings.toneMappingAlgorithm)
            geometry.setVideoScale(settings.videoScale)
            geometry.setInterpolation(settings.interpolation)
            audio.setAudioPassthrough(settings.audioPassthrough)
        } else {
            configurator.updateConfig(userOptions, settings)
        }
    }

    fun updateSettings(
        settings: UserSettings = configurator.currentState.settings,
        userOptions: Map<String, String> = configurator.currentState.userOptions
    ) = configure(userOptions, settings)

    /**
     * Optional pre-warming for native core before media load or surface attachment.
     */
    fun warmUp() {
        ensureNativeInitialized()
    }

    internal fun ensureNativeInitialized() {
        if (isInitialized.compareAndSet(false, true)) {
            if (!engineJob.isActive) {
                engineJob = SupervisorJob()
                engineScope = CoroutineScope(engineJob + Dispatchers.Default)
            }
            try {
                // A prior release() sticks requestPlay() off until the audio focus manager is
                // reset - its contract says re-initialization clears it. Dropping this call made
                // every post-release load open paused with playback permanently suppressed.
                playback.resetAudioFocus()
                // The real bridge requires the application context; only fakes accept null.
                bridge.create(context)
                configurator.currentState.userOptions["af"]?.let { audio.userBaseAf = it }
                configurator.applyInitial(bridge, configurator.currentState.userOptions, configurator.currentState.settings)
                displayRefreshRateProvider()?.let { rate ->
                    geometry.updateDisplayRefreshRate(rate)
                }
                subsystems.forEach { it.registerObservers() }
                observePlaybackEvents()
                bridge.init()
            } catch (t: Throwable) {
                dev.gyro.engine.trace.EngineLog.e("GyroPlayerEngine", "Failed to initialize GyroPlayerEngine", t)
                engineJob.cancel()
                isInitialized.set(false)
            }
        } else {
            configurator.upgradeIfDefaultsOnly(bridge, configurator.currentState.userOptions, configurator.currentState.settings)
        }
    }

    private fun observePlaybackEvents() = engineScope.launch(
        Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED
    ) {
        bridge.events.collect { event ->
            store.batch {
                subsystems.forEach { it.onEvent(event) }
            }
        }
    }

    // =========================================================================
    // Playback Controls
    // =========================================================================

    override fun play() = playback.play()
    override fun pause() = playback.pause()
    override fun togglePlayPause() = playback.togglePlayPause()
    override fun setSpeed(speed: Float) = playback.setSpeed(speed)
    override fun toggleHwdec() = playback.toggleHwdec()
    override fun setHwdec(target: String) = playback.setHwdec(target)
    override fun cycleHwdec(): String = playback.cycleHwdec()
    override fun abandonAudioFocus() = playback.abandonAudioFocus()

    override fun seekTo(positionMs: Long) = timeline.seekTo(positionMs)
    override fun seekRelative(offsetMs: Long) = timeline.seekRelative(offsetMs)
    override fun seekToChapter(index: Int) = timeline.seekToChapter(index)

    override fun cycleAbLoop() = abLoop.cycleLoopPoint()
    fun clearAbLoop() = abLoop.clearLoop()

    override fun setResizeMode(mode: dev.gyro.engine.model.ResizeMode) = geometry.setResizeMode(mode)
    override fun setVolume(volume: Int) = audio.setSoftwareVolume(volume.toDouble())

    override fun setHdrOutputMode(mode: String) = geometry.setHdrOutputMode(mode)
    override fun setToneMappingAlgorithm(algorithm: String) = geometry.setToneMappingAlgorithm(algorithm)
    override fun setVideoScale(scale: String) = geometry.setVideoScale(scale)
    override fun setInterpolation(enabled: Boolean) = geometry.setInterpolation(enabled)
    override fun setAudioPassthrough(enabled: Boolean) = audio.setAudioPassthrough(enabled)

    /** 对当前画面截图（含字幕），保存到 [targetFile]；命令返回后以文件落盘为准。 */
    override fun screenshotToFile(targetFile: java.io.File): Boolean = runCatching {
        bridge.command(arrayOf("screenshot-to-file", targetFile.absolutePath, "subtitles"))
        val ok = targetFile.exists() && targetFile.length() > 0L
        if (ok) {
            android.util.Log.i("GyroPlayerEngine", "screenshot saved: ${targetFile.absolutePath}")
        } else {
            android.util.Log.w("GyroPlayerEngine", "screenshot command produced no file")
        }
        ok
    }.getOrDefault(false)

    // =========================================================================
    // Media Loading & Surface Lifecycle
    // =========================================================================

    override fun load(item: MediaItem) = loadCoordinator.load(item)

    fun load(
        target: String,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L,
        initialWidth: Int = 0,
        initialHeight: Int = 0
    ) = load(
        MediaItem(
            target = target,
            title = displayTitle,
            startPositionMs = startPositionMs,
            initialDurationMs = initialDurationMs,
            initialWidth = initialWidth,
            initialHeight = initialHeight
        )
    )

    override fun load(
        uri: Uri,
        startPositionMs: Long,
        displayTitle: String?,
        initialDurationMs: Long,
        initialWidth: Int,
        initialHeight: Int
    ) = load(
        MediaItem(
            uri = uri,
            title = displayTitle,
            startPositionMs = startPositionMs,
            initialDurationMs = initialDurationMs,
            initialWidth = initialWidth,
            initialHeight = initialHeight
        )
    )

    override var isHeadlessMode: Boolean
        get() = loadCoordinator.isHeadlessMode
        set(value) { loadCoordinator.isHeadlessMode = value }

    override fun attachSurface(surface: Surface) {
        ensureNativeInitialized()
        loadCoordinator.attachSurface(surface)
    }
    fun attachSurface(surfaceToken: Any = Any()) {
        ensureNativeInitialized()
        loadCoordinator.attachSurface(surfaceToken)
    }
    override fun detachSurface() {
        loadCoordinator.detachSurface(null)
    }
    override fun detachSurface(surface: Surface?) = loadCoordinator.detachSurface(surface)
    fun detachSurface(surface: Any? = null) = loadCoordinator.detachSurface(surface)
    override fun redraw() = loadCoordinator.redraw()

    /**
     * Rebuilds the render surface after the view switched its buffer format (8-bit <-> 10-bit).
     *
     * HDR is decided per file, long after the surface was first attached, so the PQ colourspace
     * can only be applied by recreating the window surface once the new format is in effect.
     */
    override fun requestSurfaceRebuild() {
        if (isInitialized.get()) bridge.rebuildSurface()
    }

    override fun queryLiveBitrate(): Pair<Long, Long> {
        val v = bridge.getPropertyLong("video-bitrate")?.takeIf { it > 0 }
            ?: bridge.getPropertyLong("packet-video-bitrate")?.takeIf { it > 0 }
            ?: 0L
        val a = bridge.getPropertyLong("audio-bitrate")?.takeIf { it > 0 }
            ?: bridge.getPropertyLong("packet-audio-bitrate")?.takeIf { it > 0 }
            ?: 0L
        return v to a
    }

    override fun queryMediaInfo(
        fallbackTitle: String,
        fallbackUri: String,
        fallbackSize: Long
    ): MediaInfo = MediaInfoExtractor.extract(
        bridge = bridge,
        store = store,
        videoDimensions = geometry.videoDimensions.value,
        durationMs = durationMs.value,
        fallbackTitle = fallbackTitle,
        fallbackUri = fallbackUri,
        fallbackSize = fallbackSize
    )

    override fun release() {
        engineJob.cancel()
        loadCoordinator.release()
        store.batch {
            store.update {
                it.copy(
                    session = SessionPhase.Idle,
                    error = null,
                    playbackState = PlaybackState.IDLE,
                    isPlaying = false,
                    currentUri = null
                )
            }
            subsystems.forEach { it.reset() }
        }
        isInitialized.set(false)
        bridge.destroy()
    }
}
