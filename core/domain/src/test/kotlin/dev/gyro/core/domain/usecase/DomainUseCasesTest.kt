package dev.gyro.core.domain.usecase

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.engine.GyroPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class DomainUseCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var mediaRepo: FakeMediaRepo
    private lateinit var testScope: CoroutineScope

    private var serviceStartCalls = 0
    private var serviceStopCalls = 0
    private val fakeServiceLauncher = object : PlaybackServiceLauncher {
        override fun start() { serviceStartCalls++ }
        override fun stop() { serviceStopCalls++ }
    }

    private class FakeMediaRepo : MediaRepository {
        val names = mutableMapOf<String, String>()
        override suspend fun getVideos(): List<VideoMediaItem> = emptyList()
        override suspend fun getDisplayName(uri: Uri): String = names[uri.toString()] ?: "Fake Video"
    }

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        val filesDir = tempFolder.newFolder("usecase_test")
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
        historyRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)
        mediaRepo = FakeMediaRepo()
    }

    @After
    fun tearDown() {
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runCaseTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun testSavePlaybackProgressUseCase() = runCaseTest {
        val useCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val uri = TestUri("file:///test/save.mp4")

        engine.load(uri, startPositionMs = 0L, displayTitle = "Save Test")
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 12.0)
        fakeBridge.emitPropertyDouble("duration", 60.0)
        runCurrent()

        useCase()
        runCurrent()

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(12000L, progress?.positionMs)
        assertEquals(60000L, progress?.durationMs)
    }

    @Test
    fun testEndPlaybackSessionUseCase() = runCaseTest {
        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val endUseCase = EndPlaybackSessionUseCase(engine, saveUseCase, fakeServiceLauncher)

        val uri = TestUri("file:///test/end.mp4")
        engine.load(uri, startPositionMs = 0L, displayTitle = "End Test")
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 25.0)
        fakeBridge.emitPropertyDouble("duration", 100.0)
        runCurrent()

        endUseCase(requestServiceStop = true)
        runCurrent()

        assertEquals(1, serviceStopCalls)
        assertTrue(fakeBridge.properties["pause"] == true)

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(25000L, progress?.positionMs)
    }

    @Test
    fun testNavigatePlaylistUseCase() = runCaseTest {
        val navigateUseCase = NavigatePlaylistUseCase(engine)
        val item1 = VideoMediaItem(1L, "file:///1.mp4", "One", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///2.mp4", "Two", 2000L, 200L, 0, 0, 0L, "F")

        navigateUseCase.setPlaylist(listOf(item1, item2), item1)
        assertEquals(0, engine.playlist.currentIndex.value)

        val next = navigateUseCase.playNext()
        assertEquals(item2, next)
        assertEquals(1, engine.playlist.currentIndex.value)

        val prev = navigateUseCase.playPrevious()
        assertEquals(item1, prev)
        assertEquals(0, engine.playlist.currentIndex.value)
    }

    @Test
    fun testLoadMediaUseCase_resumesFromHistory() = runCaseTest {
        val uri = TestUri("file:///test/resume.mp4")
        historyRepo.saveProgress(uri, positionMs = 15000L, durationMs = 60000L)
        runCurrent()

        val loadUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = mediaRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            serviceLauncher = fakeServiceLauncher,
        )

        loadUseCase(uri, displayTitle = "Resume Video")
        runCurrent()

        assertEquals(1, serviceStartCalls)
        assertEquals(uri, engine.currentUri.value)
        assertEquals(15000L, engine.timePosMs.value)
    }
}
