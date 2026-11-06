package dev.gyro.feature.player

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import dev.gyro.core.data.repository.AndroidUriParser
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.engine.GyroPlayerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PlayerViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var playlistManager: PlaylistManager
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AndroidUriParser.parser = { str -> TestUri(str) }

        val filesDir = tempFolder.newFolder("player_vm_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getPackageName(): String = "dev.gyro.player"
        }

        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()

        settingsRepo = SettingsRepository(FakeSharedPreferences())
        historyRepo = PlaybackHistoryRepository(FakeSharedPreferences())
        playlistManager = PlaylistManager()

        viewModel = PlayerViewModel(
            context = testContext,
            engine = engine,
            settingsRepository = settingsRepo,
            historyRepository = historyRepo,
            playlistManager = playlistManager
        )
    }

    @After
    fun tearDown() {
        viewModel.onCleared()
        Dispatchers.resetMain()
    }

    @Test
    fun testAutoAdvanceOnPlaybackEnded() = runTest(testDispatcher) {
        val item1 = VideoMediaItem(1L, "file:///video1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///video2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")
        playlistManager.setPlaylist(listOf(item1, item2), 0)

        val uri1 = TestUri("file:///video1.mp4")
        viewModel.openUri(uri1)
        testScheduler.runCurrent()
        assertEquals(0, playlistManager.currentIndex.value)

        // End of first video
        fakeBridge.emitEndFile()
        // Wait for auto-advance delay (500ms)
        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()

        assertEquals(1, playlistManager.currentIndex.value)
        assertEquals("Video 2", playlistManager.currentItem?.displayName)
    }

    @Test
    fun testPeriodicSaveProgress() = runTest(testDispatcher) {
        val uri = TestUri("file:///video1.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 120.0) // 120s
        fakeBridge.emitPropertyDouble("time-pos", 30.0) // 30s
        fakeBridge.emitPropertyFlag("pause", false) // isPlaying = true
        testScheduler.runCurrent()

        // Advance 4500ms for periodic save loop (4s interval)
        testScheduler.advanceTimeBy(4500L)
        testScheduler.runCurrent()

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(30_000L, progress?.positionMs)
        assertEquals(120_000L, progress?.durationMs)

        // Pause to stop the periodic loop
        fakeBridge.emitPropertyFlag("pause", true)
        testScheduler.runCurrent()
    }

    @Test
    fun testSaveCurrentProgressOnExit() = runTest(testDispatcher) {
        val uri = TestUri("file:///video_exit.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 200.0)
        fakeBridge.emitPropertyDouble("time-pos", 45.0)
        testScheduler.runCurrent()

        viewModel.saveCurrentProgress()

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(45_000L, progress?.positionMs)
        assertEquals(200_000L, progress?.durationMs)
    }

    @Test
    fun testOpenUriSavesPreviousVideoProgress() = runTest(testDispatcher) {
        val uri1 = TestUri("file:///ep1.mp4")
        val uri2 = TestUri("file:///ep2.mp4")

        viewModel.openUri(uri1)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 100.0)
        fakeBridge.emitPropertyDouble("time-pos", 50.0)
        testScheduler.runCurrent()

        // Opening video 2 should save progress of video 1
        viewModel.openUri(uri2)
        testScheduler.runCurrent()

        val progress1 = historyRepo.getProgress(uri1)
        assertNotNull(progress1)
        assertEquals(50_000L, progress1?.positionMs)
        assertEquals(100_000L, progress1?.durationMs)
    }

    @Test
    fun testPlaylistNavigationMethods() = runTest(testDispatcher) {
        val item1 = VideoMediaItem(1L, "file:///a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        playlistManager.setPlaylist(listOf(item1, item2), 0)

        viewModel.openUri(TestUri("file:///a.mp4"))
        testScheduler.runCurrent()

        assertTrue(viewModel.playNext())
        testScheduler.runCurrent()
        assertEquals(1, playlistManager.currentIndex.value)

        assertTrue(viewModel.playPrevious())
        testScheduler.runCurrent()
        assertEquals(0, playlistManager.currentIndex.value)
    }

    @Test
    fun testResumeNoticeShownWhenResumedPastTwoSeconds() = runTest(testDispatcher) {
        val uri = TestUri("file:///resumed_video.mp4")
        // Save progress at 35 seconds
        historyRepo.saveProgress(uri, 35_000L, 100_000L)

        viewModel.openUri(uri)
        testScheduler.runCurrent()

        // When video becomes READY, resume notice is triggered
        fakeBridge.emitFileLoaded()
        testScheduler.runCurrent()

        assertEquals(35_000L, viewModel.resumeNoticePosMs.value)

        // After 2500ms delay, notice is cleared
        testScheduler.advanceTimeBy(2600L)
        testScheduler.runCurrent()

        assertEquals(null, viewModel.resumeNoticePosMs.value)
    }
}
