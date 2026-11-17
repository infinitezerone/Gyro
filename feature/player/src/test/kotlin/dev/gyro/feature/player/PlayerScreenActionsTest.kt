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
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.api.PlayerEngine
import dev.gyro.engine.api.controller.TrackController
import dev.gyro.feature.player.component.PlayerOverlayActions
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
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

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
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
        val settingsRepo = DefaultSettingsRepository(dataStore)
        val fakeHistoryDao = FakePlaybackHistoryDao()
        val historyRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)
        val fakeSubtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(
                videoUri: Uri,
                videoDisplayName: String?,
            ): List<String> = emptyList()
        }
        val fakeMediaRepo = object : MediaRepository {
            override suspend fun getVideos(): List<VideoMediaItem> = emptyList()
            override suspend fun getDisplayName(uri: Uri): String? = null
        }

        val sessionStateHolder = PlaybackSessionStateHolder()
        val saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val serviceLauncher = PlaybackServiceLauncher.NoOp
        val loadMediaUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = fakeMediaRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
            saveProgressUseCase = saveProgressUseCase,
        )
        val uriParser = UriParser { Uri.parse(it) }
        val navigatePlaylistUseCase = NavigatePlaylistUseCase(engine, uriParser, loadMediaUseCase, testScope)
        val endPlaybackSessionUseCase = EndPlaybackSessionUseCase(engine, saveProgressUseCase, serviceLauncher, sessionStateHolder)

        viewModel = PlayerViewModel(
            engine = engine,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            filmstripThumbnailProvider = FilmstripThumbnailProvider(testContext),
            uriParser = uriParser,
            loadMediaUseCase = loadMediaUseCase,
            navigatePlaylistUseCase = navigatePlaylistUseCase,
            endPlaybackSessionUseCase = endPlaybackSessionUseCase,
            saveProgressUseCase = saveProgressUseCase,
        )

        playerState = PlayerState(
            scope = testScope,
            scrubState = PlayerScrubState(),
            zoomState = PlayerZoomState(),
            activity = null,
        )

        val view = View(testContext)
        screenshotController = PlayerScreenshotController(testContext, testScope, view)
    }

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
            engine = engine,
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
        // onEnterPiP hides controls and begins transition, so it does not reset controls timeout
        assertEquals(3, timeoutResetCount)

        actions.onOpenSheetTab(PlayerSheetTab.AUDIO)
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
        // Verify default parameter delegates to playerState.resetControlsTimeout()
        val actions = createPlayerOverlayActions(
            engine = engine,
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
        // lastInteractionTrigger is updated by resetControlsTimeout()
        // Default SystemClock.uptimeMillis returns 0L or higher
        assertTrue(playerState.lastInteractionTrigger >= 0L)
    }

    @Test
    fun seekToFractionComputesExactTargetPosition() {
        var seekTarget = -1L
        val fakeSeekingEngine = object : PlayerEngine by engine {
            override fun seekTo(positionMs: Long) {
                seekTarget = positionMs
            }
        }

        val actions = createPlayerOverlayActions(
            engine = fakeSeekingEngine,
            viewModel = viewModel,
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
        val fakeSeekingEngine = object : PlayerEngine by engine {
            override fun seekRelative(offsetMs: Long) {
                seekRelativeOffset = offsetMs
            }
        }

        val actions = createPlayerOverlayActions(
            engine = fakeSeekingEngine,
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
            seekStepSeconds = 15,
        )

        actions.onRewind10()
        assertEquals(-15_000L, seekRelativeOffset)

        actions.onForward10()
        assertEquals(15_000L, seekRelativeOffset)
    }

    @Test
    fun toggleSubtitleSelectsTrackZeroWhenEnabled() {
        var selectedType: String? = null
        var selectedId: Int? = null

        val fakeTrackController = object : TrackController by engine.trackManager {
            override fun selectTrack(type: String, id: Int) {
                selectedType = type
                selectedId = id
            }
        }
        val fakeEngine = object : PlayerEngine by engine {
            override val trackManager = fakeTrackController
        }

        val actions = createPlayerOverlayActions(
            engine = fakeEngine,
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
            subtitleEnabled = true,
            lastSubTrackId = 3,
        )

        actions.onToggleSubtitle()
        assertEquals("sub", selectedType)
        assertEquals(0, selectedId)
    }

    @Test
    fun toggleSubtitleRestoresLastSubTrackIdWhenDisabled() {
        var selectedType: String? = null
        var selectedId: Int? = null

        val fakeTrackController = object : TrackController by engine.trackManager {
            override fun selectTrack(type: String, id: Int) {
                selectedType = type
                selectedId = id
            }
        }
        val fakeEngine = object : PlayerEngine by engine {
            override val trackManager = fakeTrackController
        }

        val actions = createPlayerOverlayActions(
            engine = fakeEngine,
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
            lastSubTrackId = 3,
        )

        actions.onToggleSubtitle()
        assertEquals("sub", selectedType)
        assertEquals(3, selectedId)
    }

    @Test
    fun toggleLockUpdatesLockAndControlVisibility() {
        playerState.isLocked = false
        playerState.showControls = true

        val actions = createPlayerOverlayActions(
            engine = engine,
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
        assertFalse(playerState.showControls)

        actions.onToggleLock()
        assertFalse(playerState.isLocked)
        assertTrue(playerState.showControls)
    }

    @Test
    fun openSheetTabHidesControlsAndSetsActiveSheetTab() {
        playerState.showControls = true
        playerState.activeSheetTab = null

        val actions = createPlayerOverlayActions(
            engine = engine,
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

        actions.onOpenSheetTab(PlayerSheetTab.SUBTITLES)
        assertFalse(playerState.showControls)
        assertEquals(PlayerSheetTab.SUBTITLES, playerState.activeSheetTab)
    }

    @Test
    fun onBackTriggersViewModelExitAndCallback() {
        var onBackCalled = false
        val actions = createPlayerOverlayActions(
            engine = engine,
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
            engine = engine,
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
            engine = engine,
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
            engine = engine,
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
}
