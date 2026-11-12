package dev.gyro.core.playback

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
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionControllerTest {

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
    private lateinit var controller: PlaybackSessionController
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

        val filesDir = tempFolder.newFolder("controller_test")
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
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<String> = emptyList()
        }

        controller = PlaybackSessionController(
            engine = engine,
            settingsRepository = settingsRepo,
            historyRepository = historyRepo,
            subtitleRepository = fakeSubtitleRepo,
            uriParser = UriParser { TestUri(it) },
            mediaRepository = mediaRepo,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runControllerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            // The periodic checkpoint is a process-lifetime ticker: left alive it reschedules every
            // 10s of virtual time and runTest's advanceUntilIdle never idles.
            testScope.cancel()
        }
    }

    @Test
    fun testSetPlaylistAndPlay_updatesPlaylistAndLoadsEngine() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/v1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/v2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")

        controller.setPlaylistAndPlay(listOf(item1, item2), item2)
        runCurrent()

        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("Video 2", engine.playlist.currentItem?.displayName)
        assertEquals("file:///tmp/v2.mp4", controller.activeUri.value?.toString())
    }

    @Test
    fun testSetSingleAndPlay_initializesSingleItem() = runControllerTest {
        val uri = TestUri("file:///tmp/single.mp4")
        controller.setSingleAndPlay(uri, "Single Video")
        runCurrent()

        assertEquals(1, engine.playlist.items.value.size)
        assertEquals("Single Video", engine.playlist.currentItem?.displayName)
        assertEquals(uri, controller.activeUri.value)
    }

    @Test
    fun testAutoAdvance_advancesWhenPlaybackEnds() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        controller.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()

        assertEquals(0, engine.playlist.currentIndex.value)

        fakeBridge.emitEndFile()
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("file:///tmp/b.mp4", controller.activeUri.value?.toString())
    }

    @Test
    fun testAutoAdvance_skippedWhenUserInterruptsDuringGraceDelay() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        controller.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()
        advanceUntilIdle()

        fakeBridge.emitEndFile()
        runCurrent()
        assertEquals(dev.gyro.engine.model.PlaybackState.ENDED, engine.playbackState.value)

        // The user replays (pause=false flips the lifecycle state back to READY) inside the
        // 500ms grace delay - the queued auto-advance must not clobber that choice.
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(0, engine.playlist.currentIndex.value)
        assertEquals("file:///tmp/a.mp4", controller.activeUri.value?.toString())
    }

    @Test
    fun testAutoAdvance_noDoubleAdvanceWhenUserSkipsDuringGraceDelay() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        val item3 = VideoMediaItem(3L, "file:///tmp/c.mp4", "C", 3000L, 300L, 0, 0, 0L, "F")
        controller.setPlaylistAndPlay(listOf(item1, item2, item3), item1)
        runCurrent()
        advanceUntilIdle()

        fakeBridge.emitEndFile()
        runCurrent()
        assertEquals(dev.gyro.engine.model.PlaybackState.ENDED, engine.playbackState.value)

        // The user taps "next" inside the grace delay. The cursor moves at once, but the load
        // stalls on the resume-point read, so the engine still reports the old URI - exactly the
        // window in which a URI-based guard concluded "the user did nothing" and advanced again.
        val gate = CompletableDeferred<Unit>()
        historyLookup = { gate.await(); null }
        assertTrue(controller.playNext())
        runCurrent()
        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("file:///tmp/a.mp4", engine.currentUri.value?.toString())

        advanceTimeBy(600L)
        runCurrent()
        assertEquals(1, engine.playlist.currentIndex.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals("file:///tmp/b.mp4", controller.activeUri.value?.toString())
    }

    @Test
    fun testExit_cancelsPendingAutoAdvance() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        controller.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()
        advanceUntilIdle()

        fakeBridge.emitEndFile()
        runCurrent()

        // User leaves the player during the grace delay: the pending advance must not pull the
        // next video up after exit.
        controller.exit()
        assertFalse(controller.sessionActive.value)
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(0, engine.playlist.currentIndex.value)
        assertEquals("file:///tmp/a.mp4", controller.activeUri.value?.toString())
    }

    @Test
    fun testProgressPersistence_onPause() = runControllerTest {
        val uri = TestUri("file:///tmp/progress.mp4")
        controller.openUri(uri)
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
    fun testUriSwitch_savesPreviousVideoProgress() = runControllerTest {
        val uri1 = TestUri("file:///tmp/ep1.mp4")
        val uri2 = TestUri("file:///tmp/ep2.mp4")
        controller.openUri(uri1)
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 50.0)
        fakeBridge.emitPropertyDouble("duration", 100.0)
        runCurrent()

        // Switching videos flushes the previous video's cached progress even without a pause.
        controller.openUri(uri2)
        runCurrent()
        runCurrent()

        val progress1 = historyRepo.getProgress(uri1)
        assertNotNull(progress1)
        assertEquals(50000L, progress1?.positionMs)
        assertEquals(100000L, progress1?.durationMs)
    }

    @Test
    fun testPlayNextAndPrevious() = runControllerTest {
        val item1 = VideoMediaItem(1L, "file:///tmp/1.mp4", "One", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///tmp/2.mp4", "Two", 2000L, 200L, 0, 0, 0L, "F")
        controller.setPlaylistAndPlay(listOf(item1, item2), item1)
        runCurrent()

        assertTrue(controller.playNext())
        runCurrent()
        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("Two", engine.playlist.currentItem?.displayName)

        assertTrue(controller.playPrevious())
        runCurrent()
        assertEquals(0, engine.playlist.currentIndex.value)
        assertEquals("One", engine.playlist.currentItem?.displayName)
    }

    @Test
    fun testLeavePlayer_persistsProgressPausesAndAbandonsAudioFocus() = runControllerTest {
        val uri = TestUri("file:///tmp/leave.mp4")
        controller.openUri(uri)
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 33.0)
        fakeBridge.emitPropertyDouble("duration", 99.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()
        assertTrue(controller.sessionActive.value)

        controller.leavePlayer()
        runCurrent()

        assertFalse(controller.sessionActive.value)
        assertTrue(fakeBridge.properties["pause"] == true)

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(33000L, progress?.positionMs)
        assertEquals(99000L, progress?.durationMs)
    }

    @Test
    fun testOnNotificationStop_persistsProgressAndPausesWithoutServiceStopCall() = runControllerTest {
        val uri = TestUri("file:///tmp/notif_stop.mp4")
        controller.openUri(uri)
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 15.0)
        fakeBridge.emitPropertyDouble("duration", 60.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()

        controller.onNotificationStop()
        runCurrent()

        assertFalse(controller.sessionActive.value)
        assertTrue(fakeBridge.properties["pause"] == true)

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(15000L, progress?.positionMs)
        assertEquals(60000L, progress?.durationMs)
    }

    @Test
    fun testTeardownIdempotency() = runControllerTest {
        val uri = TestUri("file:///tmp/idempotent.mp4")
        controller.openUri(uri)
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 10.0)
        fakeBridge.emitPropertyDouble("duration", 50.0)
        runCurrent()

        controller.leavePlayer()
        runCurrent()

        // Second call from service destruction or destination pop must safely do nothing
        controller.onServiceDestroyed()
        runCurrent()

        assertFalse(controller.sessionActive.value)
    }
}