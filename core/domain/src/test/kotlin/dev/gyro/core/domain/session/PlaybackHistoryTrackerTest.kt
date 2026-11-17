package dev.gyro.core.domain.session

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.usecase.EndPlaybackSessionUseCase
import dev.gyro.core.domain.usecase.LoadMediaUseCase
import dev.gyro.core.domain.usecase.NavigatePlaylistUseCase
import dev.gyro.core.domain.usecase.SavePlaybackProgressUseCase
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mockUriRule = MockUriRule()

    private var testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var historyLookup: suspend (Uri) -> PlaybackProgress?
    private lateinit var mediaRepo: FakeMediaRepository
    private lateinit var sessionStateHolder: PlaybackSessionStateHolder
    private lateinit var loadMediaUseCase: LoadMediaUseCase
    private lateinit var saveProgressUseCase: SavePlaybackProgressUseCase
    private lateinit var endPlaybackSessionUseCase: EndPlaybackSessionUseCase
    private lateinit var navigatePlaylistUseCase: NavigatePlaylistUseCase
    private lateinit var tracker: PlaybackHistoryTracker
    private lateinit var testScope: CoroutineScope

    private class FakeMediaRepository : MediaRepository {
        val displayNames = mutableMapOf<String, String>()
        override suspend fun getVideos(): List<VideoMediaItem> = emptyList()
        override suspend fun getDisplayName(uri: Uri): String =
            displayNames[uri.toString()] ?: uri.lastPathSegment ?: "Unknown"
    }

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        val filesDir = tempFolder.newFolder("tracker_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getPackageName(): String = "dev.gyro.player"
        }

        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge,
            resolver = FakeMediaResourceResolver()
        )

        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val settingsFile = File(filesDir, "user_settings.json")
        val dataStore = UserSettingsDataStoreFactory.createForFile(settingsFile, testScope)
        settingsRepo = DefaultSettingsRepository(dataStore)

        val fakeHistoryDao = FakePlaybackHistoryDao()
        val backingHistoryRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)
        historyLookup = backingHistoryRepo::getProgress
        historyRepo = object : PlaybackHistoryRepository by backingHistoryRepo {
            override suspend fun getProgress(uri: Uri): PlaybackProgress? = historyLookup(uri)
        }
        mediaRepo = FakeMediaRepository()
        val fakeSubtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> = emptyList()
        }

        sessionStateHolder = PlaybackSessionStateHolder()
        saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val serviceLauncher = PlaybackServiceLauncher.NoOp

        val fakeThumbnailRepo = object : ThumbnailRepository {
            override suspend fun getThumbnail(uri: Uri?, timeMs: Long) = null
            override fun release() {}
        }

        loadMediaUseCase = LoadMediaUseCase(
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

        endPlaybackSessionUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveProgressUseCase,
            thumbnailRepository = fakeThumbnailRepo,
            serviceLauncher = serviceLauncher,
            sessionStateHolder = sessionStateHolder,
        )

        navigatePlaylistUseCase = NavigatePlaylistUseCase(
            engine = engine,
            loadMediaUseCase = loadMediaUseCase,
        )

        tracker = PlaybackHistoryTracker(
            engine = engine,
            sessionStateHolder = sessionStateHolder,
            navigatePlaylistUseCase = navigatePlaylistUseCase,
            saveProgressUseCase = saveProgressUseCase,
            scope = testScope,
        )
        tracker.startObserving()
    }

    @After
    fun tearDown() {
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runTrackerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testAutoAdvance_advancesWhenPlaybackEnds() = runTrackerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        loadMediaUseCase(PlaybackSource.FromPlaylist(listOf(item1, item2), item1))
        runCurrent()

        assertEquals(0, engine.playlist.currentIndex.value)

        fakeBridge.emitEndFile()
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("file:///tmp/b.mp4", engine.currentUri.value?.toString())
    }

    @Test
    fun testPeriodicCheckpoint_savesProgressWhilePlaying() = runTrackerTest {
        val item = VideoMediaItem(1L, "file:///tmp/checkpoint.mp4", "Checkpoint", 60000L, 100L, 0, 0, 0L, "F")
        loadMediaUseCase(PlaybackSource.FromPlaylist(listOf(item), item))
        runCurrent()

        fakeBridge.emitPropertyDouble("duration", 60.0)
        fakeBridge.emitPropertyDouble("time-pos", 10.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()

        advanceTimeBy(10_500L)
        runCurrent()

        val progress = historyRepo.getProgress(TestUri("file:///tmp/checkpoint.mp4"))
        assertNotNull(progress)
        assertEquals(10000L, progress?.positionMs)
    }

    @Test
    fun testPeriodicCheckpoint_savesProgressAfterBackwardSeek() = runTrackerTest {
        val uri = TestUri("file:///tmp/rewind_checkpoint.mp4")
        val item = VideoMediaItem(1L, uri.toString(), "Rewind", 120_000L, 100L, 0, 0, 0L, "F")
        loadMediaUseCase(PlaybackSource.FromPlaylist(listOf(item), item))
        runCurrent()

        fakeBridge.emitPropertyDouble("duration", 120.0)
        fakeBridge.emitPropertyDouble("time-pos", 50.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()

        advanceTimeBy(10_500L)
        runCurrent()
        assertEquals(50_000L, historyRepo.getProgress(uri)?.positionMs)

        // Rewind backward from 50s to 12s; next checkpoint must still persist 12s
        fakeBridge.emitPropertyDouble("time-pos", 12.0)
        runCurrent()

        advanceTimeBy(10_500L)
        runCurrent()
        assertEquals(12_000L, historyRepo.getProgress(uri)?.positionMs)
    }
}
