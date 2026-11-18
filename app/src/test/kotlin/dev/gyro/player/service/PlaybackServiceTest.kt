package dev.gyro.player.service

import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.gyro.core.data.repository.DefaultPlaybackHistoryRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.DefaultThumbnailRepository
import dev.gyro.core.data.repository.MediaRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.SubtitleRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.domain.model.PlaybackSource
import dev.gyro.core.domain.model.SessionEndReason
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
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.testing.fakes.FakeMediaResourceResolver
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakePlaybackHistoryDao
import dev.gyro.core.testing.rules.MockUriRule
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.engine.MpvNativeBridge
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies [PlaybackService.handleCommand] sticky/stop semantics and [SessionPlayerAdapter]
 * command/state mapping over [dev.gyro.core.domain.session.PlaybackSession].
 */
@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val mockUriRule = MockUriRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var testContext: Context
    private lateinit var testScope: CoroutineScope
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine
    private lateinit var settingsRepo: DefaultSettingsRepository
    private lateinit var sessionStateHolder: PlaybackSessionStateHolder
    private lateinit var playlistStateHolder: PlaylistStateHolder
    private lateinit var session: DefaultPlaybackSession
    private lateinit var service: PlaybackService
    private lateinit var testLooper: Looper

    private var notificationStopCalled = false
    private var stopAndResetCalled = false

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val filesDir: File = tempFolder.newFolder("playback_service_test")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
            override fun getPackageName(): String = "dev.gyro.player"
        }
        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            resolver = FakeMediaResourceResolver(),
        )
        engine.warmUp()

        val dataStore = UserSettingsDataStoreFactory.createForFile(
            File(filesDir, "user_settings.json"),
            testScope,
        )
        settingsRepo = DefaultSettingsRepository(dataStore)
        val historyRepo = DefaultPlaybackHistoryRepository(FakePlaybackHistoryDao(), testScope)
        val mediaRepo = object : MediaRepository {
            override suspend fun getVideos(): List<VideoMediaItem> = emptyList()
            override suspend fun getDisplayName(uri: Uri): String? = uri.lastPathSegment
        }
        val subtitleRepo = object : SubtitleRepository {
            override suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String?): List<Uri> = emptyList()
        }
        val thumbnailRepo = DefaultThumbnailRepository(testContext, testScope, testDispatcher, testDispatcher)

        sessionStateHolder = PlaybackSessionStateHolder()
        playlistStateHolder = PlaylistStateHolder()
        val saveProgressUseCase = SavePlaybackProgressUseCase(engine, historyRepo)
        val serviceLauncher = PlaybackServiceLauncher.NoOp
        val loadMediaUseCase = LoadMediaUseCase(
            engine = engine,
            mediaRepository = mediaRepo,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            subtitleRepository = subtitleRepo,
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

        session = DefaultPlaybackSession(
            engine = engine,
            settingsRepository = settingsRepo,
            mpvConfigRepository = MpvConfigRepository.Empty,
            subtitleRepository = subtitleRepo,
            sessionStateHolder = sessionStateHolder,
            playlistStateHolder = playlistStateHolder,
            loadMediaUseCase = loadMediaUseCase,
            navigatePlaylistUseCase = navigatePlaylistUseCase,
            handleAppBackgroundUseCase = handleAppBackgroundUseCase,
            endPlaybackSessionUseCase = endPlaybackSessionUseCase,
            observePlaybackSessionUseCase = observePlaybackSessionUseCase,
            scope = testScope,
        )

        service = PlaybackService()
        service.session = session

        testLooper = mockk<Looper>(relaxed = true) {
            every { thread } returns Thread.currentThread()
        }

        notificationStopCalled = false
        stopAndResetCalled = false
    }

    @After
    fun tearDown() {
        engine.release()
        testScope.cancel()
        Dispatchers.resetMain()
    }

    private fun runServiceTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            body()
        } finally {
            session.endSession(SessionEndReason.SERVICE_STOPPED)
            testScope.cancel()
        }
    }

    private fun buildAdapter(onInvalidate: () -> Unit = {}): SessionPlayerAdapter = SessionPlayerAdapter(
        session = session,
        mainDispatcher = testDispatcher,
        compressionDispatcher = testDispatcher,
        looper = testLooper,
        coroutineScope = CoroutineScope(SupervisorJob() + testDispatcher),
        onInvalidateStateForTest = onInvalidate,
    )

    private fun dispatch(action: String?): Int = service.handleCommand(
        action = action,
        onNotificationStop = { notificationStopCalled = true },
        stopAndReset = { stopAndResetCalled = true },
    )

    @Test
    fun nullAndArbitraryActionsReturnStickyWithoutSideEffects() {
        assertEquals(Service.START_STICKY, dispatch(null))
        assertEquals(Service.START_STICKY, dispatch("dev.gyro.player.ACTION_UNKNOWN"))
        assertFalse(notificationStopCalled)
        assertFalse(stopAndResetCalled)
    }

    @Test
    fun stopAndDismissActionsEndSessionWithServiceStoppedAndReturnNotSticky() {
        assertEquals(Service.START_NOT_STICKY, dispatch(PlaybackService.ACTION_STOP))
        assertTrue(notificationStopCalled)
        assertTrue(stopAndResetCalled)

        notificationStopCalled = false
        stopAndResetCalled = false
        assertEquals(Service.START_NOT_STICKY, dispatch(PlaybackService.ACTION_DISMISS))
        assertTrue(notificationStopCalled)
        assertTrue(stopAndResetCalled)

        // Default callback ends active session with SERVICE_STOPPED semantics
        engine.setBackgroundPlaybackAllowed(true)
        session.load(PlaybackSource.Single(Uri.parse("file:///stop_default.mp4"), "Stop Default"))
        testDispatcher.scheduler.runCurrent()
        assertTrue(session.sessionInfo.value.isActive)

        var stopped = false
        assertEquals(
            Service.START_NOT_STICKY,
            service.handleCommand(
                action = PlaybackService.ACTION_STOP,
                stopAndReset = { stopped = true },
            ),
        )
        testDispatcher.scheduler.runCurrent()
        assertTrue(stopped)
        assertFalse(session.sessionInfo.value.isActive)
        assertNull(session.currentUri.value)
    }

    @Test
    fun adapterPlayPauseAndIntraItemSeekDelegateToSession() = runServiceTest {
        engine.setBackgroundPlaybackAllowed(true)
        session.load(PlaybackSource.Single(Uri.parse("file:///clip.mp4"), "Clip"))
        runCurrent()
        fakeBridge.emitFileLoaded(mediaTitle = "Clip")
        fakeBridge.emitPropertyDouble("duration", 120.0)
        runCurrent()

        val adapter = buildAdapter()
        try {
            adapter.setPlayWhenReadyForTest(false)
            assertEquals(true, fakeBridge.properties["pause"])

            adapter.setPlayWhenReadyForTest(true)
            assertEquals(false, fakeBridge.properties["pause"])

            adapter.seekForTest(0, 15_000L, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            val seekCmd = fakeBridge.findCommands("seek").last()
            assertEquals(listOf("seek", "15.0", "absolute"), seekCmd)

            adapter.setSpeedForTest(1.5f)
            assertEquals(1.5, fakeBridge.properties["speed"] as Double, 0.001)
        } finally {
            adapter.releaseForTest()
        }
    }

    @Test
    fun adapterCrossItemNavigationRoutesThroughSessionPlaylist() = runServiceTest {
        val item0 = VideoMediaItem(1L, "file:///ep0.mp4", "Ep 0", 60_000L, 100L, 0, 0, 0L, "F")
        val item1 = VideoMediaItem(2L, "file:///ep1.mp4", "Ep 1", 60_000L, 200L, 0, 0, 0L, "F")
        val item2 = VideoMediaItem(3L, "file:///ep2.mp4", "Ep 2", 60_000L, 300L, 0, 0, 0L, "F")

        engine.setBackgroundPlaybackAllowed(true)
        session.load(PlaybackSource.FromPlaylist(listOf(item0, item1, item2), item0))
        runCurrent()
        fakeBridge.emitFileLoaded(mediaTitle = "Ep 0")
        runCurrent()

        val adapter = buildAdapter()
        try {
            adapter.seekForTest(2, C.TIME_UNSET, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            runCurrent()
            assertEquals(2, session.playlist.currentIndex.value)
            assertEquals("file:///ep2.mp4", session.currentUri.value?.toString())

            adapter.seekForTest(1, C.TIME_UNSET, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            runCurrent()
            assertEquals(1, session.playlist.currentIndex.value)
            assertEquals("file:///ep1.mp4", session.currentUri.value?.toString())
        } finally {
            adapter.releaseForTest()
        }
    }

    @Test
    fun adapterProducesDistinctMediaItemUidsForDuplicatePlaylistUris() = runServiceTest {
        val item0 = VideoMediaItem(1L, "file:///dup.mp4", "First Dup", 10_000L, 100L, 0, 0, 0L, "F")
        val item1 = VideoMediaItem(2L, "file:///dup.mp4", "Second Dup", 10_000L, 100L, 0, 0, 0L, "F")

        engine.setBackgroundPlaybackAllowed(true)
        session.load(PlaybackSource.FromPlaylist(listOf(item0, item1), item0))
        runCurrent()

        val adapter = buildAdapter()
        try {
            val state = adapter.buildStateForTest()
            assertEquals(2, state.playlist.size)
            assertFalse(state.playlist[0].uid == state.playlist[1].uid)
            assertEquals(2, state.timeline.windowCount)
        } finally {
            adapter.releaseForTest()
        }
    }

    @Test
    fun adapterMapsSessionErrorToIdleWithPlayerErrorAndInvalidatesOnSessionChange() = runServiceTest {
        settingsRepo.setDefaultHwdec(HwdecMode.SOFTWARE)
        runCurrent()

        var invalidations = 0
        val adapter = buildAdapter { invalidations++ }
        try {
            runCurrent()
            invalidations = 0

            engine.setBackgroundPlaybackAllowed(true)
            session.load(PlaybackSource.Single(Uri.parse("file:///broken.mkv"), "Broken"))
            runCurrent()

            fakeBridge.emitEndFile(MpvNativeBridge.EndReason.ERROR)
            runCurrent()

            assertTrue(invalidations > 0)
            val state = adapter.buildStateForTest()
            assertEquals(Player.STATE_IDLE, state.playbackState)
            assertNotNull(state.playerError)
        } finally {
            adapter.releaseForTest()
        }
    }
}
