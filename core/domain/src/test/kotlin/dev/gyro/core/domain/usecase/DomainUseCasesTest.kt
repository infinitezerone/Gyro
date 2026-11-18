package dev.gyro.core.domain.usecase

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import dev.gyro.core.data.repository.CustomFolderRepository
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.PlaybackHistoryRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.data.repository.ThumbnailRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.model.MediaFeedQuery
import dev.gyro.core.domain.model.PlaybackFailureReason
import dev.gyro.core.domain.model.PlaybackSessionEvent
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.PlaylistStep
import dev.gyro.core.domain.model.SessionEndReason
import dev.gyro.core.domain.service.PlaybackServiceLauncher
import dev.gyro.core.domain.session.DefaultPlaybackSession
import dev.gyro.core.domain.session.PlaybackSessionStateHolder
import dev.gyro.core.domain.session.PlaylistStateHolder
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.model.PlaybackError
import dev.gyro.engine.model.PlaybackSnapshot
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @get:Rule
    val mockUriRule = MockUriRule()

    private var testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var fakeHistoryDao: FakePlaybackHistoryDao
    private lateinit var historyRepo: PlaybackHistoryRepository
    private lateinit var subtitleRepo: SubtitleRepository
    private lateinit var mediaRepo: FakeMediaRepo
    private lateinit var sessionStateHolder: PlaybackSessionStateHolder
    private lateinit var playlistStateHolder: PlaylistStateHolder
    private lateinit var testScope: CoroutineScope

    private var serviceStartCalls = 0
    private var serviceStopCalls = 0
    private var thumbnailReleaseCalls = 0

    private val fakeServiceLauncher = object : PlaybackServiceLauncher {
        override fun start() { serviceStartCalls++ }
        override fun stop() { serviceStopCalls++ }
    }

    private val fakeThumbnailRepo = object : ThumbnailRepository {
        override suspend fun getThumbnail(uri: Uri?, timeMs: Long) = null
        override fun release() { thumbnailReleaseCalls++ }
    }

    private class FakeMediaRepo : MediaRepository {
        var videos: List<VideoMediaItem> = emptyList()
        var getVideosCalls = 0
        val names = mutableMapOf<String, String>()
        var onGetDisplayName: (suspend (Uri) -> String?)? = null
        override suspend fun getVideos(): List<VideoMediaItem> {
            getVideosCalls++
            return videos
        }
        override suspend fun getDisplayName(uri: Uri): String? =
            onGetDisplayName?.invoke(uri) ?: names[uri.toString()] ?: "Fake Video"
    }

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        serviceStartCalls = 0
        serviceStopCalls = 0
        thumbnailReleaseCalls = 0

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

        fakeHistoryDao = FakePlaybackHistoryDao()
        historyRepo = DefaultPlaybackHistoryRepository(fakeHistoryDao, testScope)
        subtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> = emptyList()
            override suspend fun openSubtitle(subtitleUri: Uri, consume: suspend (String) -> Boolean): Boolean =
                consume(subtitleUri.toString())
        }
        mediaRepo = FakeMediaRepo()
        sessionStateHolder = PlaybackSessionStateHolder()
        playlistStateHolder = PlaylistStateHolder()
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
    fun testSavePlaybackProgressUseCase_guardsMismatchedExpectedUri() = runCaseTest {
        val useCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val uri1 = TestUri("file:///test/save1.mp4")
        val uri2 = TestUri("file:///test/save2.mp4")

        engine.load(uri1, startPositionMs = 0L, displayTitle = "Save Test")
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 12.0)
        fakeBridge.emitPropertyDouble("duration", 60.0)
        runCurrent()

        // Mismatched expectedUri must not write uri1's position under uri2
        useCase(expectedUri = uri2)
        runCurrent()
        assertNull(historyRepo.getProgress(uri2))

        useCase(expectedUri = uri1)
        runCurrent()

        val progress = historyRepo.getProgress(uri1)
        assertNotNull(progress)
        assertEquals(12000L, progress?.positionMs)
        assertEquals(60000L, progress?.durationMs)
        assertEquals("Save Test", fakeHistoryDao.getByUri(uri1.toString())?.title)
    }

    private fun buildLoadUseCase(
        subRepo: SubtitleRepository = subtitleRepo,
    ) = LoadMediaUseCase(
        engine = engine,
        mediaRepository = mediaRepo,
        historyRepository = historyRepo,
        settingsRepository = settingsRepo,
        subtitleRepository = subRepo,
        serviceLauncher = fakeServiceLauncher,
        sessionStateHolder = sessionStateHolder,
        playlistStateHolder = playlistStateHolder,
        saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo),
        scope = testScope,
    )

    @Test
    fun testEndPlaybackSessionUseCase_distinguishesEndReason() = runCaseTest {
        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val endUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveUseCase,
            thumbnailRepository = fakeThumbnailRepo,
            serviceLauncher = fakeServiceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
        )

        sessionStateHolder.setSessionActive(true)
        val uri = TestUri("file:///test/end.mp4")
        engine.load(uri, startPositionMs = 0L, displayTitle = "End Test")
        runCurrent()

        fakeBridge.emitPropertyDouble("time-pos", 25.0)
        fakeBridge.emitPropertyDouble("duration", 100.0)
        runCurrent()

        endUseCase(SessionEndReason.USER_EXIT)
        runCurrent()

        assertEquals(1, serviceStopCalls)
        assertEquals(1, thumbnailReleaseCalls)
        assertTrue(fakeBridge.properties["pause"] == true)
        assertNull(engine.currentUri.value)
        assertTrue(playlistStateHolder.items.value.isEmpty())

        val progress = historyRepo.getProgress(uri)
        assertNotNull(progress)
        assertEquals(25000L, progress?.positionMs)

        // SERVICE_STOPPED must not request redundant service stop
        sessionStateHolder.setSessionActive(true)
        endUseCase(SessionEndReason.SERVICE_STOPPED)
        runCurrent()
        assertEquals(1, serviceStopCalls)
        assertEquals(2, thumbnailReleaseCalls)
    }

    @Test
    fun testEndPlaybackSession_cancelsInFlightPlaylistLoadAndPreventsZombiePlayback() = runCaseTest {
        var lookupCancelled = false
        mediaRepo.onGetDisplayName = {
            try {
                awaitCancellation()
            } finally {
                lookupCancelled = true
            }
        }

        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val loadUseCase = buildLoadUseCase()
        val navigatePlaylistUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadUseCase)
        val endUseCase = EndPlaybackSessionUseCase(
            engine = engine,
            saveProgressUseCase = saveUseCase,
            thumbnailRepository = fakeThumbnailRepo,
            serviceLauncher = fakeServiceLauncher,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
        )

        val item1 = VideoMediaItem(1L, "content://videos/1", "", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "content://videos/2", "", 2000L, 200L, 0, 0, 0L, "F")
        playlistStateHolder.setPlaylist(listOf(item1, item2), 0)

        assertTrue(navigatePlaylistUseCase(PlaylistStep.Next))
        runCurrent()
        assertNull(engine.currentUri.value)

        endUseCase(SessionEndReason.USER_EXIT)
        runCurrent()

        assertTrue(lookupCancelled)
        assertNull(engine.currentUri.value)
        assertEquals(1, thumbnailReleaseCalls)
    }

    @Test
    fun testNavigatePlaylistUseCase_stepsAndBounds() = runCaseTest {
        val loadUseCase = buildLoadUseCase()
        val navigatePlaylistUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadUseCase)

        val item1 = VideoMediaItem(1L, "file:///1.mp4", "One", 1000L, 100L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(2L, "file:///2.mp4", "Two", 2000L, 200L, 0, 0, 0L, "F")

        loadUseCase(PlaybackSource.FromPlaylist(listOf(item1, item2), item1))
        runCurrent()
        assertEquals(0, playlistStateHolder.currentIndex.value)
        assertFalse(navigatePlaylistUseCase(PlaylistStep.Previous))

        assertTrue(navigatePlaylistUseCase(PlaylistStep.Next))
        runCurrent()
        assertEquals(1, playlistStateHolder.currentIndex.value)
        assertFalse(navigatePlaylistUseCase(PlaylistStep.Next))

        assertTrue(navigatePlaylistUseCase(PlaylistStep.Previous))
        runCurrent()
        assertEquals(0, playlistStateHolder.currentIndex.value)

        assertTrue(navigatePlaylistUseCase(PlaylistStep.AtIndex(1)))
        runCurrent()
        assertEquals(1, playlistStateHolder.currentIndex.value)
        assertFalse(navigatePlaylistUseCase(PlaylistStep.AtIndex(99)))
    }

    @Test
    fun testLoadMediaUseCase_resumesFromHistory() = runCaseTest {
        val uri = TestUri("file:///test/resume.mp4")
        historyRepo.saveProgress(uri, positionMs = 15000L, durationMs = 60000L)
        runCurrent()

        val loadUseCase = buildLoadUseCase()

        loadUseCase(PlaybackSource.UriItem(uri = uri, displayTitle = "Resume Video"))
        runCurrent()

        assertEquals(1, serviceStartCalls)
        assertEquals(uri, engine.currentUri.value)
        assertEquals(15000L, engine.timePosMs.value)
    }

    @Test
    fun testHandleAppBackgroundUseCase_checkpointsAndRespectsBackgroundAudioSetting() = runCaseTest {
        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val bgUseCase = HandleAppBackgroundUseCase(
            engine = engine,
            settingsRepository = settingsRepo,
            saveProgressUseCase = saveUseCase,
            sessionStateHolder = sessionStateHolder,
            scope = testScope,
        )

        sessionStateHolder.setSessionActive(true)
        val uri = TestUri("file:///test/bg.mp4")
        engine.load(uri, startPositionMs = 0L, displayTitle = "BG Test")
        runCurrent()

        fakeBridge.emitFileLoaded()
        fakeBridge.emitPropertyDouble("duration", 120.0)
        fakeBridge.emitPropertyDouble("time-pos", 33.0)
        fakeBridge.emitPropertyFlag("pause", false)
        runCurrent()

        // Default backgroundAudio is false -> checkpoints progress and pauses playback
        bgUseCase(isPip = false)
        runCurrent()

        assertEquals(33_000L, historyRepo.getProgress(uri)?.positionMs)
        assertTrue(fakeBridge.properties["pause"] == true)
    }

    @Test
    fun testHandleAppBackgroundUseCase_pausesBufferingAndSavesPausedPosition() = runCaseTest {
        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val bgUseCase = HandleAppBackgroundUseCase(
            engine = engine,
            settingsRepository = settingsRepo,
            saveProgressUseCase = saveUseCase,
            sessionStateHolder = sessionStateHolder,
            scope = testScope,
        )

        sessionStateHolder.setSessionActive(true)
        engine.setBackgroundPlaybackAllowed(true)
        val uri = TestUri("file:///test/buffering_bg.mp4")
        engine.load(uri, startPositionMs = 0L, displayTitle = "Buffering BG")
        runCurrent()

        // Even while BUFFERING (isPlaying == false), backgrounding must disarm headless and pause
        bgUseCase(isPip = false)
        runCurrent()

        assertFalse(engine.isBackgroundPlaybackAllowed)
        assertTrue(fakeBridge.properties["pause"] == true)

        // When paused with a scrub position, backgrounding must still checkpoint the paused position
        engine.attachSurface()
        fakeBridge.emitFileLoaded(mediaTitle = "Buffering BG")
        fakeBridge.emitPropertyDouble("duration", 100.0)
        fakeBridge.emitPropertyDouble("time-pos", 42.0)
        fakeBridge.emitPropertyFlag("pause", true)
        runCurrent()

        bgUseCase(isPip = false)
        runCurrent()

        assertEquals(42_000L, historyRepo.getProgress(uri)?.positionMs)
        assertEquals("Buffering BG", fakeHistoryDao.getByUri(uri.toString())?.title)
    }

    @Test
    fun testHwdecFallbackIsDrivenByTypedPlaybackErrorThenReportsCodecCrashOnExplicitRetry() = runCaseTest {
        val holder = PlaybackSessionStateHolder()
        holder.setSessionActive(true)

        val events = mutableListOf<PlaybackSessionEvent>()
        val collector = backgroundScope.launch { holder.events.collect { events.add(it) } }
        runCurrent()

        val uri = TestUri("content://media/external/video/media/hw_fail")
        var appliedHwdec: HwdecMode? = null
        var reloads = 0

        fun failureSnapshot() = PlaybackSnapshot(
            playbackState = PlaybackState.ERROR,
            currentUri = uri,
            hwdecCurrent = "mediacodec",
            error = PlaybackError.PlaybackFailed("failed to decode", RuntimeException("boom")),
        )

        holder.onEngineSnapshot(
            snapshot = failureSnapshot(),
            defaultHwdecMode = HwdecMode.HARDWARE_DIRECT,
            currentTimePosMs = 0L,
            setEngineHwdec = { appliedHwdec = it },
            reloadUri = { _, _, _, _, _ -> reloads++ },
            reloadRawTarget = { },
        )
        runCurrent()

        assertEquals(HwdecMode.SOFTWARE, appliedHwdec)
        assertEquals(1, reloads)
        assertTrue(events.any { it is PlaybackSessionEvent.HwdecAutoFallback })
        assertNull(holder.failureReason.value)

        // Switching back to hardware by hand and failing again must not loop: the one-shot retry is
        // already spent, so the failure surfaces as a decoder crash instead of falling back twice.
        holder.onExplicitHwdecSelected(HwdecMode.HARDWARE_DIRECT)
        holder.onEngineSnapshot(
            snapshot = failureSnapshot(),
            defaultHwdecMode = HwdecMode.HARDWARE_DIRECT,
            currentTimePosMs = 0L,
            setEngineHwdec = { appliedHwdec = it },
            reloadUri = { _, _, _, _, _ -> reloads++ },
            reloadRawTarget = { },
        )

        assertEquals(1, reloads)
        assertTrue(holder.failureReason.value is PlaybackFailureReason.CodecCrash)

        collector.cancel()
    }

    @Test
    fun testObservePlaybackSessionUseCase_seedsSynchronously() = runCaseTest {
        val observeSession = ObservePlaybackSessionUseCase(sessionStateHolder, engine, testScope)
        val uri = TestUri("file:///test/active.mp4")
        sessionStateHolder.setSessionActive(true)
        engine.load(uri, displayTitle = "Active")
        runCurrent()

        val stateFlow = observeSession()
        assertTrue(stateFlow.value.isActive)
        assertEquals(uri, stateFlow.value.activeUri)
    }

    @Test
    fun testObserveMediaFeedUseCase_reactsToCustomFoldersWithoutRedundantScanOnSearch() = runCaseTest {
        val customFoldersFlow = MutableStateFlow<Set<Uri>>(emptySet())
        val fakeCustomFolderRepo = object : CustomFolderRepository {
            override val savedFolderUris = customFoldersFlow
            override suspend fun addFolder(treeUri: Uri) = true
            override suspend fun removeFolder(treeUri: Uri) {}
            override suspend fun getFolderDetails(): List<CustomFolderDetail> = emptyList()
            override suspend fun scanCustomFolders(): List<VideoMediaItem> = emptyList()
        }

        val v1 = VideoMediaItem(1L, "file:///a.mp4", "Alpha.mp4", 1000L, 10L, 0, 0, 0L, "Movies")
        val v2 = VideoMediaItem(2L, "file:///b.mp4", "Beta.mkv", 2000L, 20L, 0, 0, 0L, "Anime")
        mediaRepo.videos = listOf(v1, v2)

        val observeFeed = ObserveMediaFeedUseCase(mediaRepo, fakeCustomFolderRepo, historyRepo)
        val queryFlow = MutableStateFlow(MediaFeedQuery(hasPermission = true))

        val feedState = observeFeed(queryFlow).stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = dev.gyro.core.domain.model.MediaFeed(),
        )
        runCurrent()

        val initial = feedState.first { !it.isLoading }
        assertEquals(1, mediaRepo.getVideosCalls)
        assertEquals(2, initial.filteredVideos.size)
        assertEquals(listOf("Anime", "Movies"), initial.folders)

        // Search query filters in-memory without re-scanning MediaRepository
        queryFlow.value = queryFlow.value.copy(searchQuery = "beta")
        runCurrent()
        val searched = feedState.value
        assertEquals("Beta.mkv", searched.filteredVideos.single().displayName)
        assertEquals(1, mediaRepo.getVideosCalls)

        // Adding a custom folder in Settings triggers a fresh scan
        customFoldersFlow.value = setOf(TestUri("content://tree/custom"))
        runCurrent()
        assertEquals(2, mediaRepo.getVideosCalls)
    }

    @Test
    fun testLoadMediaUseCase_attachesCompanionSubtitlesAndRejectsStaleDescriptor() = runCaseTest {
        val subUri = TestUri("content://subs/1.srt")
        var findCalls = 0
        var openCalls = 0
        var lastAccepted: Boolean? = null
        var staleAccepted: Boolean? = null
        var openGate: CompletableDeferred<Unit>? = null
        val trackingSubRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> {
                findCalls++
                return listOf(subUri)
            }

            override suspend fun openSubtitle(subtitleUri: Uri, consume: suspend (String) -> Boolean): Boolean {
                openCalls++
                val gate = openGate
                if (gate != null) {
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        staleAccepted = consume("fdclose://stale")
                        throw e
                    }
                }
                val accepted = consume("fdclose://42")
                lastAccepted = accepted
                return accepted
            }
        }

        val loadUseCase = buildLoadUseCase(subRepo = trackingSubRepo)

        val uri = TestUri("file:///test/movie.mkv")
        loadUseCase(PlaybackSource.Single(uri = uri, displayTitle = "Movie"))
        runCurrent()

        // While AwaitingSurface, companion subtitles must NOT be queried or opened
        assertEquals(0, findCalls)
        assertEquals(0, openCalls)
        assertTrue(fakeBridge.findCommands("sub-add").isEmpty())

        // Attaching surface moves phase to Submitting, still before FILE_LOADED
        engine.attachSurface()
        runCurrent()
        assertEquals(0, findCalls)
        assertEquals(0, openCalls)
        assertTrue(fakeBridge.findCommands("sub-add").isEmpty())

        // Once FILE_LOADED arrives (SessionPhase.Loaded), subtitles are opened & attached
        fakeBridge.emitFileLoaded()
        runCurrent()

        assertEquals(1, findCalls)
        assertEquals(1, openCalls)
        assertEquals(true, lastAccepted)
        assertTrue(fakeBridge.findCommands("sub-add").any { it.contains("fdclose://42") })

        // Verify a stale descriptor is rejected if another load supersedes it mid-open
        val gate = CompletableDeferred<Unit>()
        openGate = gate
        val uri2 = TestUri("file:///test/movie2.mkv")
        val uri3 = TestUri("file:///test/movie3.mkv")
        loadUseCase(PlaybackSource.Single(uri = uri2, displayTitle = "Movie 2"))
        runCurrent()
        fakeBridge.emitFileLoaded()
        runCurrent()
        assertEquals(2, openCalls)

        // Supersede uri2 with uri3 before openGate completes -> stale descriptor must be rejected
        openGate = null
        loadUseCase(PlaybackSource.Single(uri = uri3, displayTitle = "Movie 3"))
        runCurrent()
        assertEquals(false, staleAccepted)
        assertEquals(1, fakeBridge.findCommands("sub-add").size)
    }

    @Test
    fun testLoadMediaUseCase_skipsSubtitlesOnFailedLoad() = runCaseTest {
        val subUri = TestUri("content://subs/1.srt")
        var findCalls = 0
        var openCalls = 0
        val trackingSubRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> {
                findCalls++
                return listOf(subUri)
            }

            override suspend fun openSubtitle(subtitleUri: Uri, consume: suspend (String) -> Boolean): Boolean {
                openCalls++
                return consume("fdclose://99")
            }
        }

        val loadUseCase = buildLoadUseCase(subRepo = trackingSubRepo)

        settingsRepo.setDefaultHwdec(HwdecMode.SOFTWARE)
        runCurrent()

        engine.attachSurface()
        val uri = TestUri("file:///test/broken.mkv")
        loadUseCase(PlaybackSource.Single(uri = uri, displayTitle = "Broken"))
        runCurrent()

        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
        runCurrent()

        assertEquals(0, findCalls)
        assertEquals(0, openCalls)
        assertTrue(fakeBridge.findCommands("sub-add").isEmpty())
    }

    @Test
    fun testObserveMediaFeedUseCase_isolatesConcurrentCollectorsAndSkipsRescanOnProgress() = runCaseTest {
        val customFoldersFlow = MutableStateFlow<Set<Uri>>(emptySet())
        val fakeCustomFolderRepo = object : CustomFolderRepository {
            override val savedFolderUris = customFoldersFlow
            override suspend fun addFolder(treeUri: Uri) = true
            override suspend fun removeFolder(treeUri: Uri) {}
            override suspend fun getFolderDetails(): List<CustomFolderDetail> = emptyList()
            override suspend fun scanCustomFolders(): List<VideoMediaItem> = emptyList()
        }

        val v1 = VideoMediaItem(1L, "file:///a.mp4", "Alpha.mp4", 1000L, 10L, 0, 0, 0L, "Movies")
        mediaRepo.videos = listOf(v1)

        val observeFeed = ObserveMediaFeedUseCase(mediaRepo, fakeCustomFolderRepo, historyRepo)
        val query1 = MutableStateFlow(MediaFeedQuery(hasPermission = true))
        val query2 = MutableStateFlow(MediaFeedQuery(hasPermission = false))

        val feed1 = observeFeed(query1).stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = dev.gyro.core.domain.model.MediaFeed(),
        )
        runCurrent()
        assertEquals(1, feed1.value.rawVideos.size)

        // Second collector without permission must not overwrite first collector's cache
        val feed2 = observeFeed(query2).stateIn(
            scope = backgroundScope,
            started = SharingStarted.Eagerly,
            initialValue = dev.gyro.core.domain.model.MediaFeed(),
        )
        runCurrent()
        assertEquals(0, feed2.value.rawVideos.size)
        assertEquals(1, feed1.value.rawVideos.size)

        // Saving progress (10s checkpoint) updates playbackMap without re-scanning mediaRepository
        val scansBeforeProgress = mediaRepo.getVideosCalls
        historyRepo.saveProgress(TestUri("file:///a.mp4"), 5_000L, 10_000L, "Alpha")
        runCurrent()
        assertEquals(scansBeforeProgress, mediaRepo.getVideosCalls)
        assertEquals(5_000L, feed1.value.playbackMap["file:///a.mp4"]?.positionMs)
    }

    @Test
    fun testHandleAppBackgroundWithAudioEnabled_permitsHeadlessAutoAdvanceWithoutSurface() = runCaseTest {
        settingsRepo.setBackgroundAudio(true)
        runCurrent()

        val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val loadUseCase = buildLoadUseCase()
        val bgUseCase = HandleAppBackgroundUseCase(
            engine = engine,
            settingsRepository = settingsRepo,
            saveProgressUseCase = saveUseCase,
            sessionStateHolder = sessionStateHolder,
            scope = testScope,
        )

        val item1 = VideoMediaItem(1L, "file:///test/a.mp4", "Ep 1", 1000L, 100L, 0, 0, 0L, "Anime")
        val item2 = VideoMediaItem(2L, "file:///test/b.mp4", "Ep 2", 2000L, 200L, 0, 0, 0L, "Anime")
        engine.attachSurface()
        loadUseCase(PlaybackSource.FromPlaylist(listOf(item1, item2), item1))
        runCurrent()

        bgUseCase(isPip = false)
        runCurrent()
        assertTrue(engine.isBackgroundPlaybackAllowed)

        fakeBridge.emitEndFile(MpvNativeBridge.EndReason.EOF)
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, playlistStateHolder.currentIndex.value)
        assertEquals("file:///test/b.mp4", engine.currentUri.value?.toString())
    }

    @Test
    fun testHeadlessHwdecFallback_reloadsViaUseCaseAndAttachesCompanionSubtitles() = runCaseTest {
        val subUri = TestUri("content://subs/ep1.srt")
        val trackingSubRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> =
                listOf(subUri)

            override suspend fun openSubtitle(subtitleUri: Uri, consume: suspend (String) -> Boolean): Boolean =
                consume("fdclose://77")
        }
        val loadUseCase = buildLoadUseCase(subRepo = trackingSubRepo)

        val emittedEvents = mutableListOf<PlaybackSessionEvent>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            sessionStateHolder.events.collect { emittedEvents.add(it) }
        }
        try {
            engine.setBackgroundPlaybackAllowed(true)
            val uri = TestUri("file:///test/ep1.mkv")
            loadUseCase(PlaybackSource.Single(uri = uri, displayTitle = "Ep 1"))
            runCurrent()

            fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
            runCurrent()

            assertTrue(emittedEvents.any { it is PlaybackSessionEvent.HwdecAutoFallback })
            assertEquals(HwdecMode.SOFTWARE, engine.state.value.hwdecMode)

            fakeBridge.emitFileLoaded()
            runCurrent()

            assertTrue(fakeBridge.findCommands("sub-add").any { it.contains("fdclose://77") })
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun testSleepTimer_countsDownInApplicationScopeAndCancelsOnSessionEnd() = runCaseTest {
        val sessionScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
            val loadUseCase = buildLoadUseCase()
            val navUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadUseCase)
            val bgUseCase = HandleAppBackgroundUseCase(engine, settingsRepo, saveUseCase, sessionStateHolder, testScope)
            val endUseCase = EndPlaybackSessionUseCase(
                engine = engine,
                saveProgressUseCase = saveUseCase,
                thumbnailRepository = fakeThumbnailRepo,
                serviceLauncher = fakeServiceLauncher,
                sessionStateHolder = sessionStateHolder,
                playlistStateHolder = playlistStateHolder,
            )
            val observeUseCase = ObservePlaybackSessionUseCase(sessionStateHolder, engine, testScope)
            val session = DefaultPlaybackSession(
                engine = engine,
                settingsRepository = settingsRepo,
                mpvConfigRepository = MpvConfigRepository.Empty,
                subtitleRepository = subtitleRepo,
                sessionStateHolder = sessionStateHolder,
                playlistStateHolder = playlistStateHolder,
                loadMediaUseCase = loadUseCase,
                navigatePlaylistUseCase = navUseCase,
                handleAppBackgroundUseCase = bgUseCase,
                endPlaybackSessionUseCase = endUseCase,
                observePlaybackSessionUseCase = observeUseCase,
                scope = sessionScope,
            )

            engine.attachSurface()
            session.load(PlaybackSource.Single(TestUri("file:///test/sleep.mp4"), displayTitle = "Sleep"))
            runCurrent()
            fakeBridge.emitFileLoaded()
            session.play()
            runCurrent()

            session.setSleepTimer(1)
            assertEquals(60L, session.sleepTimerSecondsRemaining.value)

            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(0L, session.sleepTimerSecondsRemaining.value)
            assertTrue(fakeBridge.properties["pause"] == true)

            session.setSleepTimer(5)
            assertEquals(300L, session.sleepTimerSecondsRemaining.value)
            session.endSession(SessionEndReason.USER_EXIT)
            runCurrent()
            assertEquals(0L, session.sleepTimerSecondsRemaining.value)
        } finally {
            sessionScope.cancel()
        }
    }

    @Test
    fun testSettingsUpdateDuringActiveHwdecFallback_preservesSoftwareOverrideUntilDefaultHwdecChanges() = runCaseTest {
        val sessionScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            engine.warmUp()
            val saveUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
            val loadUseCase = buildLoadUseCase()
            val navUseCase = NavigatePlaylistUseCase(playlistStateHolder, sessionStateHolder, loadUseCase)
            val bgUseCase = HandleAppBackgroundUseCase(engine, settingsRepo, saveUseCase, sessionStateHolder, testScope)
            val endUseCase = EndPlaybackSessionUseCase(
                engine = engine,
                saveProgressUseCase = saveUseCase,
                thumbnailRepository = fakeThumbnailRepo,
                serviceLauncher = fakeServiceLauncher,
                sessionStateHolder = sessionStateHolder,
                playlistStateHolder = playlistStateHolder,
            )
            val observeUseCase = ObservePlaybackSessionUseCase(sessionStateHolder, engine, testScope)
            val session = DefaultPlaybackSession(
                engine = engine,
                settingsRepository = settingsRepo,
                mpvConfigRepository = MpvConfigRepository.Empty,
                subtitleRepository = subtitleRepo,
                sessionStateHolder = sessionStateHolder,
                playlistStateHolder = playlistStateHolder,
                loadMediaUseCase = loadUseCase,
                navigatePlaylistUseCase = navUseCase,
                handleAppBackgroundUseCase = bgUseCase,
                endPlaybackSessionUseCase = endUseCase,
                observePlaybackSessionUseCase = observeUseCase,
                scope = sessionScope,
            )
            runCurrent()

            engine.setBackgroundPlaybackAllowed(true)
            session.load(PlaybackSource.Single(TestUri("file:///test/hw_fallback_settings.mkv"), displayTitle = "HW Test"))
            runCurrent()
            fakeBridge.emitFileLoaded(hwdec = "mediacodec")
            runCurrent()

            fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
            runCurrent()
            assertEquals("no", fakeBridge.properties["hwdec"])

            // Unrelated setting change must NOT clobber active auto-fallback software decode override
            settingsRepo.setSeekStepSeconds(20)
            runCurrent()
            assertEquals("no", fakeBridge.properties["hwdec"])

            // Explicit default decoder preference change takes effect cleanly
            settingsRepo.setDefaultHwdec(HwdecMode.HARDWARE_COPY)
            runCurrent()
            assertEquals("mediacodec-copy", fakeBridge.properties["hwdec"])

            session.endSession(SessionEndReason.USER_EXIT)
            runCurrent()
        } finally {
            sessionScope.cancel()
        }
    }
}
