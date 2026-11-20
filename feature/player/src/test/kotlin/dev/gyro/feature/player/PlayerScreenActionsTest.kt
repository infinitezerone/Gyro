package dev.gyro.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.DefaultThumbnailRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PowerRepository
import dev.gyro.core.data.repository.ScreenshotRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.DefaultPlaybackSession
import dev.gyro.core.domain.session.PlaybackSession
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.HandleAppBackgroundUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.ObservePlaybackSessionUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.model.ResizeMode
import dev.gyro.engine.model.TrackType
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import dev.gyro.feature.player.gesture.PlayerZoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerScreenActionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mockUriRule = MockUriRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var thumbnailRepo: DefaultThumbnailRepository
    private lateinit var fakeSubtitleRepo: SubtitleRepository
    private lateinit var fakeScreenshotRepo: ScreenshotRepository
    private lateinit var fakePowerRepo: PowerRepository
    private lateinit var loadMediaUseCase: LoadMediaUseCase
    private lateinit var navigatePlaylistUseCase: NavigatePlaylistUseCase
    private lateinit var endPlaybackSessionUseCase: EndPlaybackSessionUseCase
    private lateinit var baseSession: PlaybackSession
    private lateinit var viewModel: PlayerViewModel
    private lateinit var playerState: PlayerState
    private lateinit var screenshotController: PlayerScreenshotController

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testScope = CoroutineScope(SupervisorJob() + testDispatcher)

        val filesDir = tempFolder.newFolder("actions_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getPackageName(): String = "dev.gyro.player"
        }

        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge,
            resolver = FakeMediaResourceResolver(),
        )

        val settingsFile = File(filesDir, "user_settings.json")
        val dataStore = UserSettingsDataStoreFactory.createForFile(settingsFile, testScope)
        settingsRepo = DefaultSettingsRepository(dataStore)
        val fakeHistoryDao = FakePlaybackHistoryDao()
        historyRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)
        fakeSubtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(
                videoUri: Uri,
                videoDisplayName: String?,
            ): List<Uri> = emptyList()
        }
        fakeScreenshotRepo = object : ScreenshotRepository {
            override suspend fun saveScreenshot(bitmap: android.graphics.Bitmap, mediaTitle: String, positionMs: Long): Boolean = true
        }
        fakePowerRepo = object : PowerRepository {
            override suspend fun readPowerMetrics(): PowerMetrics = PowerMetrics()
        }
        val fakeMediaRepo = object : MediaRepository {
            override suspend fun getVideos(): List<VideoMediaItem> = emptyList()
            override suspend fun getDisplayName(uri: Uri): String? = null
        }

        val sessionStateHolder = PlaybackSessionStateHolder()
        val playlistStateHolder = PlaylistStateHolder()
        val saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val serviceLauncher = PlaybackServiceLauncher.NoOp
        thumbnailRepo = DefaultThumbnailRepository(testContext, testScope, testDispatcher, testDispatcher)
        loadMediaUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = fakeMediaRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            subtitleRepository = fakeSubtitleRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
            saveProgressUseCase = saveProgressUseCase,
            scope = testScope,
        )
        navigatePlaylistUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadMediaUseCase)
        val handleAppBackgroundUseCase = HandleAppBackgroundUseCase(
            engine = engine,
            settingsRepository = settingsRepo,
            saveProgressUseCase = saveProgressUseCase,
            sessionStateHolder = sessionStateHolder,
            scope = testScope,
        )
        endPlaybackSessionUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveProgressUseCase,
            thumbnailRepository = thumbnailRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
        )
        val observePlaybackSessionUseCase = ObservePlaybackSessionUseCase(sessionStateHolder, engine, testScope)
        baseSession = DefaultPlaybackSession(
            engine = engine,
            settingsRepository = settingsRepo,
            mpvConfigRepository = MpvConfigRepository.Empty,
            subtitleRepository = fakeSubtitleRepo,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
            loadMediaUseCase = loadMediaUseCase,
            navigatePlaylistUseCase = navigatePlaylistUseCase,
            handleAppBackgroundUseCase = handleAppBackgroundUseCase,
            endPlaybackSessionUseCase = endPlaybackSessionUseCase,
            observePlaybackSessionUseCase = observePlaybackSessionUseCase,
            scope = testScope,
        )

        viewModel = buildViewModel(baseSession)

        playerState = PlayerState(
            scrubState = PlayerScrubState(),
            zoomState = PlayerZoomState(),
            activity = null,
        )

        val view = View(testContext)
        screenshotController = PlayerScreenshotController(
            context = testContext,
            scope = testScope,
            view = view,
            onSaveScreenshot = fakeScreenshotRepo::saveScreenshot,
        )
    }

    private fun buildViewModel(targetSession: PlaybackSession = baseSession): PlayerViewModel = PlayerViewModel(
        session = targetSession,
        settingsRepository = settingsRepo,
        thumbnailRepository = thumbnailRepo,
        screenshotRepository = fakeScreenshotRepo,
        powerRepository = fakePowerRepo,
    )

    @After
    fun tearDown() {
        viewModel.onUserExit()
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun all16ActionsCallResetControlsTimeoutInvariant() {
        var timeoutResetCount = 0
        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 100_000L },
            timePosMsProvider = { 42_000L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test Video" },
            subtitleEnabled = true,
            lastSubTrackId = 2,
            seekStepSeconds = 15,
            onEnterPiP = {},
            onResetControlsTimeout = { timeoutResetCount++ },
        )

        actions.onTogglePlayPause()
        assertEquals(1, timeoutResetCount)

        actions.onToggleLock()
        assertEquals(2, timeoutResetCount)

        actions.onSeekToFraction(0.5f)
        assertEquals(3, timeoutResetCount)

        actions.onEnterPiP()
        assertEquals(3, timeoutResetCount)

        actions.onOpenSheetTab(PlayerSheetRoute.AUDIO)
        assertEquals(4, timeoutResetCount)

        actions.onToggleOrientation()
        assertEquals(5, timeoutResetCount)

        actions.onBack()
        assertEquals(6, timeoutResetCount)

        actions.onPlayPrevious()
        assertEquals(7, timeoutResetCount)

        actions.onPlayNext()
        assertEquals(8, timeoutResetCount)

        actions.onSelectPlaylistItem(0)
        assertEquals(9, timeoutResetCount)

        actions.onRewind10()
        assertEquals(10, timeoutResetCount)

        actions.onForward10()
        assertEquals(11, timeoutResetCount)

        actions.onToggleSubtitle()
        assertEquals(12, timeoutResetCount)

        actions.onCycleAbLoop()
        assertEquals(13, timeoutResetCount)

        actions.onClearAbLoop()
        assertEquals(14, timeoutResetCount)

        actions.onScreenshot()
        assertEquals(15, timeoutResetCount)
    }

    @Test
    fun defaultOnResetControlsTimeoutInvokesPlayerState() {
        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 100_000L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        actions.onTogglePlayPause()
        assertTrue(playerState.lastInteractionTrigger >= 0L)
    }

    @Test
    fun seekToFractionComputesExactTargetPosition() {
        var seekTarget = -1L
        val fakeSeekingSession = object : PlaybackSession by baseSession {
            override fun seekTo(positionMs: Long) {
                seekTarget = positionMs
            }
        }

        val actions = createPlayerOverlayActions(
            viewModel = buildViewModel(fakeSeekingSession),
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 200_000L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        actions.onSeekToFraction(0.75f)
        assertEquals(150_000L, seekTarget)
    }

    @Test
    fun seekRelativeUsesConfiguredSeekStepSeconds() {
        var seekRelativeOffset = 0L
        val fakeSeekingSession = object : PlaybackSession by baseSession {
            override fun seekRelative(offsetMs: Long) {
                seekRelativeOffset = offsetMs
            }
        }

        val actions = createPlayerOverlayActions(
            viewModel = buildViewModel(fakeSeekingSession),
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 100_000L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
            seekStepSeconds = 15,
        )

        actions.onRewind10()
        assertEquals(-15_000L, seekRelativeOffset)

        actions.onForward10()
        assertEquals(15_000L, seekRelativeOffset)
    }

    @Test
    fun toggleSubtitleSelectsTrackZeroWhenEnabled() {
        var selectedType: TrackType? = null
        var selectedId: Int? = null

        val fakeSession = object : PlaybackSession by baseSession {
            override fun selectTrack(type: TrackType, id: Int) {
                selectedType = type
                selectedId = id
            }
        }

        val actions = createPlayerOverlayActions(
            viewModel = buildViewModel(fakeSession),
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = true,
            lastSubTrackId = 3,
        )

        actions.onToggleSubtitle()
        assertEquals(TrackType.SUBTITLE, selectedType)
        assertEquals(0, selectedId)
    }

    @Test
    fun toggleSubtitleRestoresLastSubTrackIdWhenDisabled() {
        var selectedType: TrackType? = null
        var selectedId: Int? = null

        val fakeSession = object : PlaybackSession by baseSession {
            override fun selectTrack(type: TrackType, id: Int) {
                selectedType = type
                selectedId = id
            }
        }

        val actions = createPlayerOverlayActions(
            viewModel = buildViewModel(fakeSession),
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 3,
        )

        actions.onToggleSubtitle()
        assertEquals(TrackType.SUBTITLE, selectedType)
        assertEquals(3, selectedId)
    }

    @Test
    fun toggleLockUpdatesLockAndControlVisibility() {
        playerState.isLocked = false
        playerState.showControls = true

        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        actions.onToggleLock()
        assertTrue(playerState.isLocked)
        assertTrue(playerState.showControls)

        actions.onToggleLock()
        assertFalse(playerState.isLocked)
        assertTrue(playerState.showControls)
    }

    @Test
    fun openSheetTabHidesControlsAndSetsActiveSheetTab() {
        playerState.showControls = true
        playerState.activeSheetTab = null

        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        actions.onOpenSheetTab(PlayerSheetRoute.SUBTITLES)
        assertFalse(playerState.showControls)
        assertEquals(PlayerSheetRoute.SUBTITLES, playerState.activeSheetTab)
    }

    @Test
    fun onBackTriggersViewModelExitAndCallback() {
        var onBackCalled = false
        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = { onBackCalled = true },
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        actions.onBack()
        assertTrue(onBackCalled)
    }

    @Test
    fun toggleOrientationSwitchesScreenOrientation() {
        var requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val fakeActivity = object : Activity() {
            override fun setRequestedOrientation(requested: Int) {
                requestedOrientation = requested
            }
        }

        val landscapeConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }

        val landscapeActions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = fakeActivity,
            configuration = landscapeConfig,
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        landscapeActions.onToggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, requestedOrientation)

        val portraitConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }

        val portraitActions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = fakeActivity,
            configuration = portraitConfig,
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
        )

        portraitActions.onToggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requestedOrientation)
    }

    @Test
    fun enterPiPInvokesOnEnterPiPCallback() {
        var enteredPiP = false
        val actions = createPlayerOverlayActions(
            viewModel = viewModel,
            playerState = playerState,
            screenshotController = screenshotController,
            activity = null,
            configuration = Configuration(),
            onBack = {},
            durationMsProvider = { 0L },
            timePosMsProvider = { 0L },
            surfaceViewProvider = { null },
            mediaTitleProvider = { "Test" },
            subtitleEnabled = false,
            lastSubTrackId = 1,
            onEnterPiP = { enteredPiP = true },
        )

        actions.onEnterPiP()
        assertTrue(enteredPiP)
    }

    @Test
    fun createPlayerSheetCallbacksRoutesMutationsThroughViewModel() {
        var chapterIndex = -1
        var selectedSpeed = 0f
        var hwdecTarget: HwdecMode? = null
        var debandEnabled = false
        var softwareVolume = 0.0
        var subDelay = 0.0
        var resizeMode: ResizeMode? = null
        var selectedShader: GlslShader? = null
        var selectedTrack: Pair<TrackType, Int>? = null

        val fakeSession = object : PlaybackSession by baseSession {
            override fun setDeband(enabled: Boolean) {
                debandEnabled = enabled
            }

            override fun setSoftwareVolume(gain: Double) {
                softwareVolume = gain
            }

            override fun setSubtitleDelay(delaySec: Double) {
                subDelay = delaySec
            }

            override fun setResizeMode(mode: ResizeMode) {
                resizeMode = mode
            }

            override fun setShader(shader: GlslShader, customPath: String?, customName: String?) {
                selectedShader = shader
            }

            override fun selectTrack(type: TrackType, id: Int) {
                selectedTrack = type to id
            }

            override fun seekToChapter(index: Int) {
                chapterIndex = index
            }

            override fun setSpeed(speed: Float) {
                selectedSpeed = speed
            }

            override fun setHwdec(mode: HwdecMode) {
                hwdecTarget = mode
            }
        }

        playerState.activeSheetTab = PlayerSheetRoute.AUDIO
        val callbacks = createPlayerSheetCallbacks(
            viewModel = buildViewModel(fakeSession),
            playerState = playerState,
        )

        callbacks.onSelectChapter(3)
        assertEquals(3, chapterIndex)
        assertEquals(null, playerState.activeSheetTab)

        callbacks.onSelectSpeed(1.5f)
        assertEquals(1.5f, selectedSpeed, 0.001f)

        callbacks.onSetHwdec(HwdecMode.SOFTWARE)
        assertEquals(HwdecMode.SOFTWARE, hwdecTarget)

        callbacks.onSetDeband(true)
        assertTrue(debandEnabled)

        callbacks.onSetSoftwareVolume(145.0)
        assertEquals(145.0, softwareVolume, 0.001)

        callbacks.onSetSubDelay(0.25)
        assertEquals(0.25, subDelay, 0.001)

        callbacks.onSelectResizeMode(ResizeMode.CROP)
        assertEquals(ResizeMode.CROP, resizeMode)

        callbacks.onSelectShader(GlslShader.ANIME4K)
        assertEquals(GlslShader.ANIME4K, selectedShader)

        callbacks.onSelectTrack(TrackType.AUDIO, 2)
        assertEquals(TrackType.AUDIO to 2, selectedTrack)
    }
}
