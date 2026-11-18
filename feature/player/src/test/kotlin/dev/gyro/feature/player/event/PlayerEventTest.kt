package dev.gyro.feature.player.event

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.net.TestUri
import android.net.Uri
import android.view.Surface
import androidx.lifecycle.viewModelScope
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
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.DefaultPlaybackSession
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.HandleAppBackgroundUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.ObservePlaybackSessionUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.media.MediaResourceResolver
import dev.gyro.feature.player.PlayerViewModel
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
class PlayerEventTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mockUriRule = MockUriRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var mediaRepo: FakeMediaRepository
    private lateinit var viewModel: PlayerViewModel
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val filesDir = tempFolder.newFolder("player_event_test")
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
        engine.warmUp()

        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val settingsFile = File(filesDir, "user_settings.json")
        val dataStore = UserSettingsDataStoreFactory.createForFile(settingsFile, testScope)
        settingsRepo = DefaultSettingsRepository(dataStore)

        val fakeHistoryDao = FakePlaybackHistoryDao()
        historyRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)

        mediaRepo = FakeMediaRepository()
        val fakeSubtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(
                videoUri: Uri,
                videoDisplayName: String?,
            ): List<Uri> = emptyList()
        }
        val fakeScreenshotRepo = object : ScreenshotRepository {
            override suspend fun saveScreenshot(bitmap: android.graphics.Bitmap, mediaTitle: String, positionMs: Long): Boolean = true
        }
        val fakePowerRepo = object : PowerRepository {
            override suspend fun readPowerMetrics(): PowerMetrics = PowerMetrics()
        }

        val sessionStateHolder = PlaybackSessionStateHolder()
        val playlistStateHolder = PlaylistStateHolder()
        val saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val serviceLauncher = PlaybackServiceLauncher.NoOp
        val thumbnailRepo = DefaultThumbnailRepository(testContext, testScope, testDispatcher, testDispatcher)
        val loadMediaUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = mediaRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            subtitleRepository = fakeSubtitleRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
            saveProgressUseCase = saveProgressUseCase,
            scope = testScope,
        )
        val navigatePlaylistUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadMediaUseCase)
        val handleAppBackgroundUseCase = HandleAppBackgroundUseCase(
            engine = engine,
            settingsRepository = settingsRepo,
            saveProgressUseCase = saveProgressUseCase,
            sessionStateHolder = sessionStateHolder,
            scope = testScope,
        )
        val endPlaybackSessionUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveProgressUseCase,
            thumbnailRepository = thumbnailRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
        )
        val observePlaybackSessionUseCase = ObservePlaybackSessionUseCase(sessionStateHolder, engine, testScope)
        val session = DefaultPlaybackSession(
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

        viewModel = PlayerViewModel(
            session = session,
            settingsRepository = settingsRepo,
            thumbnailRepository = thumbnailRepo,
            screenshotRepository = fakeScreenshotRepo,
            powerRepository = fakePowerRepo,
        )
    }

    @After
    fun tearDown() {
        viewModel.onUserExit()
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runPlayerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            viewModel.viewModelScope.cancel()
            testScope.cancel()
        }
    }

    @Test
    fun testPlayAtIndexEmitsInitiatedAndCompletedEventsWithDefaultSource() = runPlayerTest {
        val item0 = VideoMediaItem(1L, "file:///item0.mp4", "Item 0", 1000L, 100L, 0, 0, 0L, "F")
        val item1 = VideoMediaItem(2L, "file:///item1.mp4", "Item 1", 2000L, 200L, 0, 0, 0L, "F")
        viewModel.setPlaylistAndPlay(listOf(item0, item1), item0)
        testScheduler.runCurrent()

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        val success = viewModel.playAtIndex(1)
        testScheduler.runCurrent()

        assertTrue(success)
        assertEquals(2, emittedEvents.size)

        val initiated = emittedEvents[0] as PlayerEvent.SwitchInitiated
        assertEquals(0, initiated.fromIndex)
        assertEquals(1, initiated.toIndex)
        assertEquals(PlaybackTriggerSource.USER_PLAYLIST_CLICK, initiated.source)

        val completed = emittedEvents[1] as PlayerEvent.SwitchCompleted
        assertEquals(1, completed.index)
        assertEquals("file:///item1.mp4", completed.uri.toString())

        collectorJob.cancel()
    }

    @Test
    fun testPlayAtIndexEmitsShortVideoSwipeSource() = runPlayerTest {
        val item0 = VideoMediaItem(1L, "file:///short0.mp4", "Short 0", 1000L, 100L, 0, 0, 0L, "F")
        val item1 = VideoMediaItem(2L, "file:///short1.mp4", "Short 1", 2000L, 300L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(3L, "file:///short2.mp4", "Short 2", 3000L, 300L, 0, 0, 0L, "F")
        viewModel.setPlaylistAndPlay(listOf(item0, item1, item2), item0)
        testScheduler.runCurrent()

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        val success = viewModel.playAtIndex(2, source = PlaybackTriggerSource.SHORT_VIDEO_SWIPE)
        testScheduler.runCurrent()

        assertTrue(success)
        assertEquals(2, emittedEvents.size)

        val initiated = emittedEvents[0] as PlayerEvent.SwitchInitiated
        assertEquals(0, initiated.fromIndex)
        assertEquals(2, initiated.toIndex)
        assertEquals(PlaybackTriggerSource.SHORT_VIDEO_SWIPE, initiated.source)

        val completed = emittedEvents[1] as PlayerEvent.SwitchCompleted
        assertEquals(2, completed.index)
        assertEquals("file:///short2.mp4", completed.uri.toString())

        collectorJob.cancel()
    }

    @Test
    fun testPlayAtIndexOutOfBoundsEmitsOnlyInitiated() = runPlayerTest {
        val item0 = VideoMediaItem(1L, "file:///item0.mp4", "Item 0", 1000L, 100L, 0, 0, 0L, "F")
        viewModel.setPlaylistAndPlay(listOf(item0), item0)
        testScheduler.runCurrent()

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        val success = viewModel.playAtIndex(99, source = PlaybackTriggerSource.SHORT_VIDEO_SWIPE)
        testScheduler.runCurrent()

        assertFalse(success)
        assertEquals(1, emittedEvents.size)

        val initiated = emittedEvents[0] as PlayerEvent.SwitchInitiated
        assertEquals(0, initiated.fromIndex)
        assertEquals(99, initiated.toIndex)
        assertEquals(PlaybackTriggerSource.SHORT_VIDEO_SWIPE, initiated.source)

        collectorJob.cancel()
    }

    @Test
    fun testHardwareDecodeFailureTriggersAutoFallbackAndReloadsAtCurrentPosition() = runPlayerTest {
        engine.attachSurface(Surface(SurfaceTexture(0)))

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        viewModel.openUri(TestUri("file:///hw_fail.mp4"), displayTitle = "HW Fail")
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded(mediaTitle = "HW Fail", hwdec = "mediacodec")
        fakeBridge.emitPropertyDouble("time-pos", 42.0)
        testScheduler.runCurrent()

        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        testScheduler.runCurrent()

        assertEquals(1, emittedEvents.size)
        val fallback = emittedEvents.single() as PlayerEvent.HwdecAutoFallback
        assertEquals(HwdecMode.HARDWARE_DIRECT, fallback.from)
        assertEquals(HwdecMode.SOFTWARE, fallback.to)
        assertEquals("no", fakeBridge.properties["hwdec"])
        assertEquals(null, viewModel.failureReason.value)

        val loadCommands = fakeBridge.findCommands("loadfile")
        assertEquals(2, loadCommands.size)
        assertTrue(loadCommands.last().any { it.contains("start=+42.000") })

        collectorJob.cancel()
    }

    @Test
    fun testSecondFailureAfterSoftwareFallbackEmitsFatalDemuxErrorWithoutLooping() = runPlayerTest {
        engine.attachSurface(Surface(SurfaceTexture(0)))

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        viewModel.openUri(TestUri("file:///double_fail.mp4"), displayTitle = "Double Fail")
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded(mediaTitle = "Double Fail", hwdec = "mediacodec")
        testScheduler.runCurrent()

        // 1st failure triggers software fallback
        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        testScheduler.runCurrent()

        // 2nd failure under software decode must NOT loop; emit fatal ErrorOccurred
        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        testScheduler.runCurrent()

        assertEquals(2, emittedEvents.size)
        assertTrue(emittedEvents[0] is PlayerEvent.HwdecAutoFallback)
        val errorEvent = emittedEvents[1] as PlayerEvent.ErrorOccurred
        assertTrue(errorEvent.reason is PlaybackFailureReason.DemuxError)
        assertTrue(viewModel.failureReason.value is PlaybackFailureReason.DemuxError)
        assertEquals(2, fakeBridge.findCommands("loadfile").size)

        collectorJob.cancel()
    }

    @Test
    fun testSoftwareDecodeModeSkipsAutoFallbackOnError() = runPlayerTest {
        engine.attachSurface(Surface(SurfaceTexture(0)))

        val emittedEvents = mutableListOf<PlayerEvent>()
        val collectorJob = backgroundScope.launch {
            viewModel.events.collect { emittedEvents.add(it) }
        }
        testScheduler.runCurrent()

        viewModel.setHwdec(HwdecMode.SOFTWARE)
        viewModel.openUri(TestUri("file:///sw_fail.mp4"), displayTitle = "SW Fail")
        testScheduler.runCurrent()

        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        testScheduler.runCurrent()

        assertEquals(1, emittedEvents.size)
        val errorEvent = emittedEvents.single() as PlayerEvent.ErrorOccurred
        assertTrue(errorEvent.reason is PlaybackFailureReason.DemuxError)
        assertTrue(viewModel.failureReason.value is PlaybackFailureReason.DemuxError)
        assertEquals(1, fakeBridge.findCommands("loadfile").size)

        collectorJob.cancel()
    }

    @Test
    fun testSourceOpenFailureClassifiesFileNotFoundAndPermissionDeniedWithoutHwdecFallback() = runPlayerTest {
        suspend fun verifyOpenFailure(
            failure: Throwable,
            uri: Uri,
            assertReason: (PlaybackFailureReason) -> Unit,
        ) {
            val localBridge = FakeMpvNativeBridge()
            val localEngine = GyroPlayerEngine(
                context = testContext,
                bridge = localBridge,
                ioDispatcher = testDispatcher,
                defaultDispatcher = testDispatcher,
                mainDispatcher = testDispatcher,
                resolver = FakeMediaResourceResolver(failure = failure),
            )
            localEngine.warmUp()
            val localSessionHolder = PlaybackSessionStateHolder()
            val localPlaylistHolder = PlaylistStateHolder()
            val localSubtitleRepo = object : SubtitleRepository {
                override suspend fun findCompanionSubtitles(
                    videoUri: Uri,
                    videoDisplayName: String?,
                ): List<Uri> = emptyList()
            }
            val localSaveProgress = SavePlaybackProgressUseCase(localEngine, historyRepo)
            val localThumbnailRepo = DefaultThumbnailRepository(testContext, testScope, testDispatcher, testDispatcher)
            val localLoadMedia = LoadMediaUseCase(
                engine = localEngine,
                mediaRepository = mediaRepo,
                historyRepository = historyRepo,
                settingsRepository = settingsRepo,
                subtitleRepository = localSubtitleRepo,
                serviceLauncher = PlaybackServiceLauncher.NoOp,
                sessionStateHolder = localSessionHolder,
                playlistStateHolder = localPlaylistHolder,
                saveProgressUseCase = localSaveProgress,
                scope = testScope,
            )
            val localNavigate = NavigatePlaylistUseCase(localPlaylistHolder, localSessionHolder, localLoadMedia)
            val localHandleBg = HandleAppBackgroundUseCase(
                engine = localEngine,
                settingsRepository = settingsRepo,
                saveProgressUseCase = localSaveProgress,
                sessionStateHolder = localSessionHolder,
                scope = testScope,
            )
            val localEnd = EndPlaybackSessionUseCase(
                engine = localEngine,
                saveProgressUseCase = localSaveProgress,
                thumbnailRepository = localThumbnailRepo,
                serviceLauncher = PlaybackServiceLauncher.NoOp,
                sessionStateHolder = localSessionHolder,
                playlistStateHolder = localPlaylistHolder,
            )
            val localObserve = ObservePlaybackSessionUseCase(localSessionHolder, localEngine, testScope)
            val localSession = DefaultPlaybackSession(
                engine = localEngine,
                settingsRepository = settingsRepo,
                mpvConfigRepository = MpvConfigRepository.Empty,
                subtitleRepository = localSubtitleRepo,
                sessionStateHolder = localSessionHolder,
                playlistStateHolder = localPlaylistHolder,
                loadMediaUseCase = localLoadMedia,
                navigatePlaylistUseCase = localNavigate,
                handleAppBackgroundUseCase = localHandleBg,
                endPlaybackSessionUseCase = localEnd,
                observePlaybackSessionUseCase = localObserve,
                scope = testScope,
            )
            val localVm = PlayerViewModel(
                session = localSession,
                settingsRepository = settingsRepo,
                thumbnailRepository = localThumbnailRepo,
                screenshotRepository = object : ScreenshotRepository {
                    override suspend fun saveScreenshot(bitmap: android.graphics.Bitmap, mediaTitle: String, positionMs: Long): Boolean = true
                },
                powerRepository = object : PowerRepository {
                    override suspend fun readPowerMetrics(): PowerMetrics = PowerMetrics()
                },
            )
            val events = mutableListOf<PlayerEvent>()
            val collector = backgroundScope.launch {
                localVm.events.collect { events.add(it) }
            }
            try {
                testScheduler.runCurrent()
                localVm.openUri(uri, displayTitle = "Unopenable")
                testScheduler.runCurrent()

                assertEquals(1, events.size)
                val errorEvent = events.single() as PlayerEvent.ErrorOccurred
                assertReason(errorEvent.reason)
                assertEquals(errorEvent.reason, localVm.failureReason.value)
                assertFalse(localBridge.properties["hwdec"] == "no")
            } finally {
                collector.cancel()
                localVm.onUserExit()
                localVm.viewModelScope.cancel()
                localEngine.release()
            }
        }

        val permissionUri = TestUri("content://media/external/video/denied")
        verifyOpenFailure(
            failure = MediaResourceResolver.MediaOpenException("denied", SecurityException("revoked")),
            uri = permissionUri,
        ) { reason ->
            assertTrue(reason is PlaybackFailureReason.PermissionDenied)
            assertEquals(permissionUri, (reason as PlaybackFailureReason.PermissionDenied).uri)
        }

        val missingUri = TestUri("content://media/external/video/missing")
        verifyOpenFailure(
            failure = MediaResourceResolver.MediaOpenException("missing", FileNotFoundException("gone")),
            uri = missingUri,
        ) { reason ->
            assertTrue(reason is PlaybackFailureReason.FileNotFound)
            assertEquals(missingUri, (reason as PlaybackFailureReason.FileNotFound).uri)
        }
    }

    @Test
    fun testAutoFallbackRestoresPreferredHwdecWhenSwitchingToNextItem() = runPlayerTest {
        engine.attachSurface(Surface(SurfaceTexture(0)))
        val item0 = VideoMediaItem(1L, "file:///ep0.mp4", "Ep 0", 1000L, 100L, 0, 0, 0L, "F")
        val item1 = VideoMediaItem(2L, "file:///ep1.mp4", "Ep 1", 2000L, 200L, 0, 0, 0L, "F")

        viewModel.setPlaylistAndPlay(listOf(item0, item1), item0)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded(mediaTitle = "Ep 0", hwdec = "mediacodec")
        testScheduler.runCurrent()

        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        testScheduler.runCurrent()
        assertEquals("no", fakeBridge.properties["hwdec"])

        assertTrue(viewModel.playNext())
        testScheduler.runCurrent()

        assertEquals("mediacodec,mediacodec-copy", fakeBridge.properties["hwdec"])
    }

    private class FakeMediaRepository : MediaRepository {
        val queriedUris = mutableListOf<Uri>()
        var displayNameLookup: suspend (Uri) -> String? = { null }

        override suspend fun getVideos(): List<VideoMediaItem> = emptyList()

        override suspend fun getDisplayName(uri: Uri): String? {
            queriedUris += uri
            return displayNameLookup(uri)
        }
    }
}
