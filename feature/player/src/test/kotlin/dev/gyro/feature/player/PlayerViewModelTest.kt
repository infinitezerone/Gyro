package dev.gyro.feature.player

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.data.repository.UriParser
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.playback.PlaybackSessionController
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.engine.GyroPlayerEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNull
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
    private lateinit var historyLookup: suspend (Uri) -> PlaybackProgress?
    private lateinit var mediaRepo: FakeMediaRepository
    private lateinit var viewModel: PlayerViewModel
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val filesDir = tempFolder.newFolder("player_vm_test")
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
        engine.warmUp()

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
            override suspend fun findCompanionSubtitles(videoUri: android.net.Uri, videoDisplayName: String?): List<String> = emptyList()
        }

        viewModel = PlayerViewModel(
            session = PlaybackSessionController(
                engine = engine,
                settingsRepository = settingsRepo,
                historyRepository = historyRepo,
                subtitleRepository = fakeSubtitleRepo,
                mediaRepository = mediaRepo,
                uriParser = UriParser { TestUri(it) },
                scope = testScope,
            ),
            settingsRepository = settingsRepo,
        )
    }

    @After
    fun tearDown() {
        viewModel.onCleared()
        // The engine owns its own scope and audio-focus callbacks; leaving them alive makes the
        // next test inherit their dispatches after Main has been reset.
        engine.release()
        // DataStore/Room collectors live on this scope; cancelling it before resetMain keeps a
        // straggler from touching Dispatchers.Main after the rule has torn it down.
        testScope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * Stops the ViewModel before `runTest` drains the scheduler.
     *
     * The checkpoint job is an unbounded `while (isActive) { delay(...) }` loop, so virtual time
     * always has a pending task. Without this teardown the drain spins forever — and it spins
     * *after* an assertion has already failed, which turns any red test into a hung build.
     */
    private fun runPlayerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            viewModel.viewModelScope.cancel()
            // Must happen inside runTest, before its final advanceUntilIdle: the session
            // checkpoint ticker is an unbounded virtual-time loop on this scope, and the @After
            // teardown only runs after the drain - cancelling there is too late.
            testScope.cancel()
        }
    }

    /**
     * Room writes are fire-and-forget on the repository scope, so the row only becomes visible once
     * that coroutine runs. Virtual time makes the wait deterministic instead of wall-clock bound.
     */
    private suspend fun TestScope.awaitProgress(uri: android.net.Uri): PlaybackProgress? {
        repeat(200) {
            runCurrent()
            historyRepo.getProgress(uri)?.let { return it }
            // Virtual time alone is not enough: part of the engine -> ViewModel -> repository chain
            // resumes on real dispatchers, so give those threads a real slice before pumping again.
            withContext(Dispatchers.Default) { Thread.sleep(5) }
        }
        return null
    }

    @Test
    fun testExplicitTitleWinsOverPlaylistWithoutQueryingProvider() = runPlayerTest {
        val uri = TestUri("content://videos/explicit")
        engine.setSingleItem(uri, "Playlist title")

        viewModel.openUri(uri, forceReload = true, displayTitle = "Explicit title")
        runCurrent()

        assertEquals(uri, engine.currentUri.value)
        assertEquals("Explicit title", engine.mediaTitle.value)
        assertTrue(mediaRepo.queriedUris.isEmpty())
    }

    @Test
    fun testOpenUriFindsKnownTitleEvenWhenItemIsNotCurrent() = runPlayerTest {
        val current = VideoMediaItem(1L, "file:///current.mp4", "Current", 1000L, 100L, 0, 0, 0L, "F")
        val target = VideoMediaItem(2L, "file:///target.mp4", "Known target", 1000L, 100L, 0, 0, 0L, "F")
        engine.setPlaylist(listOf(current, target), 0)

        viewModel.openUri(TestUri(target.uriString))
        runCurrent()

        assertEquals("Known target", engine.mediaTitle.value)
        assertTrue(mediaRepo.queriedUris.isEmpty())
    }

    @Test
    fun testPlayMediaItemPassesItsTitleWithoutPlaylistMembership() = runPlayerTest {
        val item = VideoMediaItem(1L, "file:///standalone.mp4", "Standalone", 1000L, 100L, 0, 0, 0L, "F")

        viewModel.playMediaItem(item)
        runCurrent()

        assertEquals(item.uriString, engine.currentUri.value?.toString())
        assertEquals(item.displayName, engine.mediaTitle.value)
        assertTrue(mediaRepo.queriedUris.isEmpty())
    }

    @Test
    fun testUnknownTitleQueriesMediaRepository() = runPlayerTest {
        val uri = TestUri("content://videos/unknown")
        mediaRepo.displayNameLookup = { "Provider title.mkv" }

        viewModel.openUri(uri)
        runCurrent()

        assertEquals(listOf(uri), mediaRepo.queriedUris)
        assertEquals("Provider title.mkv", engine.mediaTitle.value)
    }

    @Test
    fun testBlankExplicitAndPlaylistTitlesFallBackToQuery() = runPlayerTest {
        val uri = TestUri("content://videos/blank")
        engine.setSingleItem(uri, " ")
        mediaRepo.displayNameLookup = { "Provider title.mp4" }

        viewModel.openUri(uri, displayTitle = " ")
        runCurrent()

        assertEquals(listOf(uri), mediaRepo.queriedUris)
        assertEquals("Provider title.mp4", engine.mediaTitle.value)
    }

    @Test
    fun testUnavailableProviderTitleLeavesUriFallbackToEngine() = runPlayerTest {
        val uri = TestUri("content://videos/no-name")
        mediaRepo.displayNameLookup = { null }

        viewModel.openUri(uri)
        runCurrent()

        assertEquals(listOf(uri), mediaRepo.queriedUris)
        assertEquals(uri, engine.currentUri.value)
        assertEquals(uri.lastPathSegment, engine.mediaTitle.value)
    }

    @Test
    fun testNewOpenCancelsSuspendedTitleLookup() = runPlayerTest {
        val oldUri = TestUri("content://videos/old")
        val newUri = TestUri("content://videos/new")
        var lookupCancelled = false
        mediaRepo.displayNameLookup = {
            try {
                awaitCancellation()
            } finally {
                lookupCancelled = true
            }
        }

        viewModel.openUri(oldUri)
        runCurrent()
        assertEquals(listOf(oldUri), mediaRepo.queriedUris)
        assertNull(engine.currentUri.value)

        viewModel.openUri(newUri, displayTitle = "New title")
        runCurrent()

        assertTrue(lookupCancelled)
        assertEquals(newUri, engine.currentUri.value)
        assertEquals(newUri, viewModel.activeUri.value)
        assertEquals("New title", engine.mediaTitle.value)
    }

    @Test
    fun testLateTitleCannotOverwriteForcedReloadOfSameUri() = runPlayerTest {
        val uri = TestUri("content://videos/same")
        val oldTitle = CompletableDeferred<String?>()
        var lookupReturned = false
        var historyReads = 0
        mediaRepo.displayNameLookup = {
            // Model a provider that returns a result even after its caller is cancelled.
            val title = withContext(NonCancellable) { oldTitle.await() }
            lookupReturned = true
            title
        }
        historyLookup = {
            historyReads++
            null
        }

        try {
            viewModel.openUri(uri)
            runCurrent()
            assertEquals(listOf(uri), mediaRepo.queriedUris)
            assertNull(engine.currentUri.value)

            viewModel.openUri(uri, forceReload = true, displayTitle = "Latest title")
            runCurrent()
            assertEquals("Latest title", engine.mediaTitle.value)

            oldTitle.complete("Stale title")
            runCurrent()

            assertTrue(lookupReturned)
            assertEquals(1, historyReads)
            assertEquals(uri, engine.currentUri.value)
            assertEquals("Latest title", engine.mediaTitle.value)
        } finally {
            oldTitle.complete(null)
            runCurrent()
        }
    }

    @Test
    fun testLateHistoryCannotLoadAnOlderUri() = runPlayerTest {
        val oldUri = TestUri("content://videos/old-history")
        val newUri = TestUri("content://videos/new-history")
        val oldProgress = CompletableDeferred<PlaybackProgress?>()
        var oldLookupStarted = false
        var oldLookupReturned = false
        historyLookup = { uri ->
            if (uri == oldUri) {
                oldLookupStarted = true
                val progress = withContext(NonCancellable) { oldProgress.await() }
                oldLookupReturned = true
                progress
            } else null
        }

        try {
            viewModel.openUri(oldUri, displayTitle = "Old title")
            runCurrent()
            assertTrue(oldLookupStarted)
            assertNull(engine.currentUri.value)

            viewModel.openUri(newUri, displayTitle = "New title")
            runCurrent()
            assertEquals(newUri, engine.currentUri.value)

            oldProgress.complete(null)
            runCurrent()

            assertTrue(oldLookupReturned)
            assertEquals(newUri, engine.currentUri.value)
            assertEquals(newUri, viewModel.activeUri.value)
            assertEquals("New title", engine.mediaTitle.value)
            assertTrue(mediaRepo.queriedUris.isEmpty())
        } finally {
            oldProgress.complete(null)
            runCurrent()
        }
    }

    @Test
    fun testClearingViewModelCancelsPendingTitleLookup() = runPlayerTest {
        var lookupCancelled = false
        mediaRepo.displayNameLookup = {
            try {
                awaitCancellation()
            } finally {
                lookupCancelled = true
            }
        }
        viewModel.openUri(TestUri("content://videos/pending"))
        runCurrent()

        viewModel.onCleared()
        runCurrent()

        assertTrue(lookupCancelled)
        assertNull(engine.currentUri.value)
    }

    @Test
    fun testAutoAdvanceOnPlaybackEnded() = runPlayerTest {
        val item1 = VideoMediaItem(1L, "file:///video1.mp4", "Video 1", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///video2.mp4", "Video 2", 2000L, 200L, 0, 0, 0L, "F")
        engine.setPlaylist(listOf(item1, item2), 0)

        val uri1 = TestUri("file:///video1.mp4")
        viewModel.openUri(uri1)
        testScheduler.runCurrent()
        assertEquals(0, engine.playlist.currentIndex.value)

        // End of first video
        fakeBridge.emitEndFile()
        // Wait for auto-advance delay (500ms)
        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()

        assertEquals(1, engine.playlist.currentIndex.value)
        assertEquals("Video 2", engine.playlist.currentItem?.displayName)
    }

    @Test
    fun testPeriodicSaveProgress() = runPlayerTest {
        val uri = TestUri("file:///video1.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 120.0) // 120s
        fakeBridge.emitPropertyDouble("time-pos", 30.0) // 30s
        fakeBridge.emitPropertyFlag("pause", false) // isPlaying = true
        testScheduler.runCurrent()

        // Advance 10500ms for periodic throttled save loop (10s interval)
        testScheduler.advanceTimeBy(10500L)
        testScheduler.runCurrent()

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(30_000L, progress?.positionMs)
        assertEquals(120_000L, progress?.durationMs)

        // Pause to stop the periodic loop, then cancel the scope: an unbounded delay loop keeps
        // runTest's final advanceUntilIdle spinning in virtual time forever.
        fakeBridge.emitPropertyFlag("pause", true)
        testScheduler.runCurrent()
    }

    @Test
    fun testPauseSavesProgressImmediately() = runPlayerTest {
        val uri = TestUri("file:///video_pause.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 300.0)
        fakeBridge.emitPropertyDouble("time-pos", 45.0)
        fakeBridge.emitPropertyFlag("pause", false)
        testScheduler.runCurrent()

        // Before any periodic timer, pause immediately triggers save
        fakeBridge.emitPropertyFlag("pause", true)
        testScheduler.runCurrent()

        val progress = awaitProgress(uri)
        assertNotNull(progress)
        assertEquals(45_000L, progress?.positionMs)
    }

    @Test
    fun testSaveCurrentProgressOnExit() = runPlayerTest {
        val uri = TestUri("file:///video_exit.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 200.0)
        fakeBridge.emitPropertyDouble("time-pos", 45.0)
        testScheduler.runCurrent()

        viewModel.saveCurrentProgress()

        // Writes go to Room on the repository scope and come back through the DAO flow, so the
        // value is observable one scheduler pass later, not synchronously.
        val progress = awaitProgress(uri)
        assertNotNull(progress)
        assertEquals(45_000L, progress?.positionMs)
        assertEquals(200_000L, progress?.durationMs)
    }

    @Test
    fun testOpenUriSavesPreviousVideoProgress() = runPlayerTest {
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

        val progress1 = awaitProgress(uri1)
        assertNotNull(progress1)
        assertEquals(50_000L, progress1?.positionMs)
        assertEquals(100_000L, progress1?.durationMs)
    }

    @Test
    fun testPlaylistNavigationMethods() = runPlayerTest {
        val item1 = VideoMediaItem(1L, "file:///a.mp4", "A", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///b.mp4", "B", 2000L, 200L, 0, 0, 0L, "F")
        engine.setPlaylist(listOf(item1, item2), 0)

        viewModel.openUri(TestUri("file:///a.mp4"))
        testScheduler.runCurrent()

        assertTrue(viewModel.playNext())
        testScheduler.runCurrent()
        assertEquals(1, engine.playlist.currentIndex.value)

        assertTrue(viewModel.playPrevious())
        testScheduler.runCurrent()
        assertEquals(0, engine.playlist.currentIndex.value)
    }

    @Test
    fun testAutoResumeLoadsSavedProgressSilently() = runPlayerTest {
        val uri = TestUri("file:///resumed_video.mp4")
        // Save progress at 35 seconds; wait for it to surface through the DAO flow before the
        // resume path reads it.
        historyRepo.saveProgress(uri, 35_000L, 100_000L)
        awaitProgress(uri)

        val surface = android.view.Surface(android.graphics.SurfaceTexture(0))
        engine.attachSurface(surface)

        viewModel.openUri(uri)
        testScheduler.runCurrent()

        // Wait for IO path resolution and pump testScheduler for withContext(Dispatchers.Main)
        var loadCmd: List<String>? = null
        repeat(200) {
            runCurrent()
            loadCmd = fakeBridge.commands.find { it.firstOrNull() == "loadfile" }
            if (loadCmd != null) return@repeat
            delay(5)
        }

        assertNotNull("Expected loadfile command to be dispatched", loadCmd)
        assertTrue(loadCmd!!.any { it.contains("start=+35") })
    }

    @Test
    fun testReopenSameUriReloadsAndResumesPlayback() = runPlayerTest {
        val uri = TestUri("file:///same_video.mp4")
        viewModel.openUri(uri)
        testScheduler.runCurrent()
        fakeBridge.emitFileLoaded()
        testScheduler.runCurrent()

        // Simulate pausing on exit to list
        engine.pause()
        testScheduler.runCurrent()

        // Re-open same URI
        viewModel.openUri(uri)
        testScheduler.runCurrent()

        // Should have called load on engine and started loading
        assertEquals(uri.toString(), engine.currentUri.value?.toString())
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
