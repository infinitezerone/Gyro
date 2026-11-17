package dev.gyro.feature.player.event

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.DefaultThumbnailRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PowerRepository
import dev.gyro.core.data.repository.ScreenshotRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.PowerMetrics
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.feature.player.PlayerViewModel
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
            saveProgressUseCase = saveProgressUseCase,
            scope = testScope,
        )
        val navigatePlaylistUseCase = NavigatePlaylistUseCase(engine, loadMediaUseCase)
        val endPlaybackSessionUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveProgressUseCase,
            thumbnailRepository = thumbnailRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
        )

        viewModel = PlayerViewModel(
            engine = engine,
            settingsRepository = settingsRepo,
            thumbnailRepository = thumbnailRepo,
            subtitleRepository = fakeSubtitleRepo,
            screenshotRepository = fakeScreenshotRepo,
            powerRepository = fakePowerRepo,
            loadMediaUseCase = loadMediaUseCase,
            navigatePlaylistUseCase = navigatePlaylistUseCase,
            endPlaybackSessionUseCase = endPlaybackSessionUseCase,
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
        engine.setPlaylist(listOf(item0, item1), 0)

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
        engine.setPlaylist(listOf(item0, item1, item2), 0)

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
        engine.setPlaylist(listOf(item0), 0)

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
