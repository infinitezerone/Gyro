package dev.gyro.feature.player.session

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
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.media.PlaylistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class PlaybackSessionOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var historyLookup: suspend (Uri) -> PlaybackProgress?
    private lateinit var mediaRepo: FakeMediaRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var orchestrator: PlaybackSessionOrchestrator
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

        val filesDir = tempFolder.newFolder("orchestrator_test")
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
        playlistManager = PlaylistManager()
        val fakeSubtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<String> = emptyList()
        }

        orchestrator = PlaybackSessionOrchestrator(
            engine = engine,
            settingsRepository = settingsRepo,
            historyRepository = historyRepo,
            subtitleRepository = fakeSubtitleRepo,
            uriParser = UriParser { TestUri(it) },
            mediaRepository = mediaRepo,
            playlist = playlistManager,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runOrchestratorTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            // The orchestrator's periodic checkpoint is a process-lifetime ticker: left alive it
            // reschedules every 10s of virtual time and runTest's advanceUntilIdle never idles.
            testScope.cancel()
        }
    }

    @Test
    fun testSetPlaylistAndPlay_updatesPlaylistAndLoadsEngine() = runOrchestratorTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/v1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/v2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")

        orchestrator.setPlaylistAndPlay(listOf(item1, item2), item2)
        runCurrent()

        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("Video 2", playlistManager.currentItem?.displayName)
        assertEquals("file:///tmp/v2.mp4", orchestrator.activeUri.value?.toString())
    }

    @Test
    fun testSetSingleAndPlay_initializesSingleItem() = runOrchestratorTest {
        val uri = TestUri("file:///tmp/single.mp4")
        orchestrator.setSingleAndPlay(uri, "Single Video")
        runCurrent()

        assertEquals(1, playlistManager.items.value.size)
        assertEquals("Single Video", playlistManager.currentItem?.displayName)
        assertEquals(uri, orchestrator.activeUri.value)
    }

    @Test
    fun testAutoAdvance_advancesWhenPlaybackEnds() = runOrchestratorTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        orchestrator.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()

        assertEquals(0, playlistManager.currentIndex.value)

        fakeBridge.emitEndFile()
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("file:///tmp/b.mp4", orchestrator.activeUri.value?.toString())
    }

    @Test
    fun testAutoAdvance_skippedWhenUserInterruptsDuringGraceDelay() = runOrchestratorTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        orchestrator.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()

        fakeBridge.emitEndFile()
        runCurrent()
        assertEquals(dev.gyro.engine.model.PlaybackState.ENDED, engine.playbackState.value)

        // The user replays (pause=false flips the lifecycle state back to READY) inside the
        // 500ms grace delay - the queued auto-advance must not clobber that choice.
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(0, playlistManager.currentIndex.value)
        assertEquals("file:///tmp/a.mp4", orchestrator.activeUri.value?.toString())
    }

    @Test
    fun testProgressPersistence_onPause() = runOrchestratorTest {
        val uri = TestUri("file:///tmp/progress.mp4")
        orchestrator.openUri(uri)
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 42.0)
        fakeBridge.emitPropertyDouble("duration", 100.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()

        fakeBridge.emitPropertyFlag("pause", true)
        runCurrent()

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(42000L, progress?.positionMs)
        assertEquals(100000L, progress?.durationMs)
    }

    @Test
    fun testPlayNextAndPrevious() = runOrchestratorTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/1.mp4", "One", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/2.mp4", "Two", 2000L, 200L, 0, 0, 0L, "F")
        orchestrator.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()

        assertTrue(orchestrator.playNext())
        runCurrent()
        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("Two", playlistManager.currentItem?.displayName)

        assertTrue(orchestrator.playPrevious())
        runCurrent()
        assertEquals(0, playlistManager.currentIndex.value)
        assertEquals("One", playlistManager.currentItem?.displayName)
    }
}
