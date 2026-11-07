package dev.gyro.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.UserSettings
import dev.gyro.engine.audio.AudioFocusController
import dev.gyro.engine.audio.GyroAudioFocusManager
import dev.gyro.engine.config.MpvOptionConfigurator
import dev.gyro.engine.effects.AudioEffectsController
import dev.gyro.engine.effects.RealAudioEffectsController
import dev.gyro.engine.effects.RealVideoEffectsController
import dev.gyro.engine.effects.VideoEffectsController
import dev.gyro.engine.info.MediaInfoExtractor
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.media.MediaItem
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import dev.gyro.engine.model.SessionPhase
import dev.gyro.engine.session.MediaLoadCoordinator
import dev.gyro.engine.session.PlaybackLifecycleController
import dev.gyro.engine.session.PlaybackTimelineController
import dev.gyro.engine.session.RealMediaLoadCoordinator
import dev.gyro.engine.session.RealPlaybackLifecycleController
import dev.gyro.engine.session.RealPlaybackTimelineController
import dev.gyro.engine.session.RealVideoGeometryCoordinator
import dev.gyro.engine.session.VideoGeometryCoordinator
import dev.gyro.engine.shader.RealShaderController
import dev.gyro.engine.shader.ShaderController
import dev.gyro.engine.shader.ShaderManager
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.tracks.RealTrackAndChapterManager
import dev.gyro.engine.tracks.TrackAndChapterManager
import dev.gyro.engine.tuning.RealSyncTuningController
import dev.gyro.engine.tuning.SyncTuningController
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
class GyroPlayerEngine(
    private val context: Context? = null,
    private val bridge: MpvNativeBridge = RealMpvNativeBridge,
    private val resolver: MediaResourceResolver = if (context != null) DefaultMediaResourceResolver(context) else MediaResourceResolver.Direct,
    private val configurator: MpvOptionConfigurator = MpvOptionConfigurator(),
    internal val store: PlaybackStore = PlaybackStore(),
    val shaders: ShaderController = RealShaderController(bridge, ShaderManager(context), store),
    val geometry: VideoGeometryCoordinator = RealVideoGeometryCoordinator(bridge, store),
    val audio: AudioEffectsController = RealAudioEffectsController(bridge, store),
    val video: VideoEffectsController = RealVideoEffectsController(bridge, store),
    val trackManager: TrackAndChapterManager = RealTrackAndChapterManager(bridge, store),
    val tuning: SyncTuningController = RealSyncTuningController(bridge, store),
    internal val timeline: PlaybackTimelineController =
        RealPlaybackTimelineController(bridge, geometry, trackManager, store = store),
    audioFocus: AudioFocusController? = null,
    private val displayRefreshRateProvider: () -> Float? = { defaultDisplayRefreshRate(context) }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        bridge: MpvNativeBridge,
        resolver: MediaResourceResolver,
        configurator: MpvOptionConfigurator,
        store: PlaybackStore,
        shaders: ShaderController,
        geometry: VideoGeometryCoordinator,
        audio: AudioEffectsController,
        video: VideoEffectsController,
        trackManager: TrackAndChapterManager,
        tuning: SyncTuningController,
        timeline: PlaybackTimelineController
    ) : this(
        context = context,
        bridge = bridge,
        resolver = resolver,
        configurator = configurator,
        store = store,
        shaders = shaders,
        geometry = geometry,
        audio = audio,
        video = video,
        trackManager = trackManager,
        tuning = tuning,
        timeline = timeline,
        audioFocus = null,
        displayRefreshRateProvider = { defaultDisplayRefreshRate(context) }
    )

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

    internal val playback: PlaybackLifecycleController = RealPlaybackLifecycleController(
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
        getRequestedTitle = { loadCoordinator.requestedTitle }
    )

    internal val loadCoordinator: MediaLoadCoordinator = RealMediaLoadCoordinator(
        bridge = bridge,
        store = store,
        resolver = resolver,
        timeline = timeline,
        geometry = geometry,
        audio = audio,
        tuning = tuning,
        playbackController = playback,
        coroutineScope = { engineScope },
        ensureInitialized = { ensureInitialized() }
    )

    /**
     * Managed domain subsystems: observers, event dispatch, and state reset are orchestrated uniformly.
     */
    private val subsystems: List<EngineSubsystem> by lazy {
        listOf(geometry, trackManager, timeline, audio, video, tuning, playback)
    }

    // =========================================================================
    // Reactive State Projections (Single source of truth: store.state)
    // =========================================================================

    val state: StateFlow<PlaybackSnapshot> get() = store.state
    val playbackState: StateFlow<PlaybackState> = store.select { it.playbackState }
    val isPlaying: StateFlow<Boolean> = store.select { it.isPlaying }
    val currentUri: StateFlow<Uri?> = store.select { it.currentUri }
    val mediaTitle: StateFlow<String> = store.select { it.mediaTitle }
    val speed: StateFlow<Float> = store.select { it.speed }
    val timePosMs: StateFlow<Long> get() = timeline.timePosMs
    val durationMs: StateFlow<Long> get() = timeline.durationMs
    val bufferedPosMs: StateFlow<Long> get() = timeline.bufferedPosMs
    val isFileLoaded: Boolean get() = timeline.isFileLoaded
    val isSurfaceAttached: Boolean get() = loadCoordinator.isSurfaceAttached

    // =========================================================================
    // Core Engine Lifecycle & Native Initialization
    // =========================================================================

    fun ensureInitialized(
        userOptions: Map<String, String> = configurator.currentState.userOptions,
        settings: UserSettings = configurator.currentState.settings,
    ) {
        if (isInitialized.compareAndSet(false, true)) {
            if (!engineJob.isActive) {
                engineJob = SupervisorJob()
                engineScope = CoroutineScope(engineJob + Dispatchers.Default)
            }
            try {
                bridge.create()
                configurator.applyInitial(bridge, userOptions, settings)
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
            configurator.upgradeIfDefaultsOnly(bridge, userOptions, settings)
        }
    }

    fun updateSettings(
        settings: UserSettings = configurator.currentState.settings,
        userOptions: Map<String, String> = configurator.currentState.userOptions
    ) {
        if (isInitialized.get()) {
            configurator.applyRuntimeUpdate(bridge, settings, userOptions)
        } else {
            configurator.updateConfig(userOptions, settings)
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

    fun play() = playback.play()
    fun pause() = playback.pause()
    fun togglePlayPause() = playback.togglePlayPause()
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun toggleHwdec() = playback.toggleHwdec()
    fun abandonAudioFocus() = playback.abandonAudioFocus()

    fun seekTo(positionMs: Long) = timeline.seekTo(positionMs)
    fun seekRelative(offsetMs: Long) = timeline.seekRelative(offsetMs)
    fun seekToChapter(index: Int) = timeline.seekToChapter(index)

    // =========================================================================
    // Media Loading & Surface Lifecycle
    // =========================================================================

    fun load(item: MediaItem) = loadCoordinator.load(item)

    fun load(
        target: String,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L
    ) = load(
        MediaItem(
            target = target,
            title = displayTitle,
            startPositionMs = startPositionMs,
            initialDurationMs = initialDurationMs
        )
    )

    fun load(
        uri: Uri,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L
    ) = load(
        MediaItem(
            uri = uri,
            title = displayTitle,
            startPositionMs = startPositionMs,
            initialDurationMs = initialDurationMs
        )
    )

    @Deprecated(
        message = "Use load(uri, ...) instead",
        replaceWith = ReplaceWith("load(uri, startPositionMs, displayTitle, initialDurationMs)")
    )
    fun loadUri(
        uri: Uri,
        startPositionMs: Long = 0L,
        displayTitle: String? = null,
        initialDurationMs: Long = 0L
    ) = load(uri, startPositionMs, displayTitle, initialDurationMs)

    fun attachSurface(surface: Surface) = loadCoordinator.attachSurface(surface)
    fun attachSurface(surfaceToken: Any = Any()) = loadCoordinator.attachSurface(surfaceToken)
    fun detachSurface(surface: Any? = null) = loadCoordinator.detachSurface(surface)
    fun redraw() = loadCoordinator.redraw()

    fun queryMediaInfo(
        fallbackTitle: String = "",
        fallbackUri: String = "",
        fallbackSize: Long = 0L
    ): MediaInfo = MediaInfoExtractor.extract(
        bridge = bridge,
        store = store,
        videoDimensions = geometry.videoDimensions.value,
        durationMs = durationMs.value,
        fallbackTitle = fallbackTitle,
        fallbackUri = fallbackUri,
        fallbackSize = fallbackSize
    )

    fun release() {
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
