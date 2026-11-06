package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import android.view.Surface
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.UserSettings
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.engine.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Adversarial stress and priority collision test suite for Milestone 2:
 * 1. Concurrency stress on FakeMpvNativeBridge and GyroPlayerEngine
 * 2. Collision scenarios where mpv.conf contains protected keys
 * 3. GUI settings vs mpv.conf priority ordering
 * 4. Rapid lifecycle transitions and surface attachment/detachment
 */
class GyroPlayerEngineM2StressTest {

    /**
     * Settings over an in-memory DataStore. [SettingsRepository.updateSettings] applies to the
     * in-memory flow synchronously, which is what the assertions read immediately afterwards.
     */
    private fun testSettingsRepo(settings: UserSettings = UserSettings()): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = UserSettingsDataStoreFactory.createInMemory(settings, scope)
        val repo = DefaultSettingsRepository(store)
        // stateIn propagates asynchronously; block until the repository reports the seeded value,
        // otherwise ensureInitialized() would read the initial defaults.
        runBlocking {
            repo.updateSettings { settings }
            repo.settings.first { it == settings }
        }
        return repo
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("engine_m2_stress")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = this@GyroPlayerEngineM2StressTest.filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
    }

    // =========================================================================
    // 1. Concurrency Stress on FakeMpvNativeBridge & GyroPlayerEngine
    // =========================================================================

    @Test
    fun testConcurrency_fakeMpvNativeBridge_concurrentCommands() {
        val threadCount = 16
        val iterationsPerThread = 100
        val totalExpected = threadCount * iterationsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        fakeBridge.command(arrayOf("seek", "$i", "relative", "t=$threadId"))
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting command threads", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exceptions occurred during concurrent commands", 0, errors.get())
        val recordedCount = fakeBridge.commands.size
        assertEquals(
            "FakeMpvNativeBridge MUST be thread-safe as claimed in KDoc, but lost ${totalExpected - recordedCount} commands",
            totalExpected,
            recordedCount
        )
    }

    @Test
    fun testConcurrency_fakeMpvNativeBridge_concurrentSurfaceAttachmentAndCounters() {
        val threadCount = 16
        val iterationsPerThread = 50
        val totalExpected = threadCount * iterationsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val surface = createTestSurface()

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        fakeBridge.attachSurface(surface)
                        fakeBridge.detachSurface()
                    }
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting surface threads", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val recordedAttach = fakeBridge.attachSurfaceCount
        val recordedDetach = fakeBridge.detachSurfaceCount
        val recordedAttachedSurfaces = fakeBridge.attachedSurfaces.size

        assertEquals(
            "FakeMpvNativeBridge attachSurfaceCount must equal totalExpected, but lost ${totalExpected - recordedAttach} increments",
            totalExpected,
            recordedAttach
        )
        assertEquals(
            "FakeMpvNativeBridge detachSurfaceCount must equal totalExpected, but lost ${totalExpected - recordedDetach} increments",
            totalExpected,
            recordedDetach
        )
        assertEquals(
            "FakeMpvNativeBridge attachedSurfaces.size must equal totalExpected, but lost ${totalExpected - recordedAttachedSurfaces} items",
            totalExpected,
            recordedAttachedSurfaces
        )
    }

    @Test
    fun testConcurrency_fakeMpvNativeBridge_concurrentPropertyAccess() {
        val threadCount = 16
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        val propKey = "prop_$threadId"
                        fakeBridge.setPropertyString(propKey, "val_$i")
                        fakeBridge.setPropertyDouble("double_$threadId", i.toDouble())
                        fakeBridge.setPropertyLong("long_$threadId", i.toLong())
                        fakeBridge.setPropertyBoolean("flag_$threadId", i % 2 == 0)

                        fakeBridge.getPropertyString(propKey)
                        fakeBridge.getPropertyDouble("double_$threadId")
                        fakeBridge.getPropertyLong("long_$threadId")
                        fakeBridge.getPropertyBoolean("flag_$threadId")
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting property threads", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exceptions occurred during concurrent property access", 0, errors.get())
    }

    @Test
    fun testConcurrency_gyroPlayerEngine_concurrentEnsureInitialized() {
        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    engine.ensureInitialized()
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting ensureInitialized", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exceptions during concurrent ensureInitialized", 0, errors.get())
        // create and init must be executed exactly once despite 16 concurrent callers
        assertEquals("Bridge create must be called exactly once", 1, fakeBridge.createCount)
        assertEquals("Bridge init must be called exactly once", 1, fakeBridge.initCount)
    }

    @Test
    fun testConcurrency_gyroPlayerEngine_concurrentAudioFilterUpdates() {
        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()

        val threadCount = 12
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        when (threadId % 4) {
                            0 -> engine.setSoftwareVolume(100.0 + (i % 100))
                            1 -> engine.resetSoftwareGain()
                            2 -> engine.setNightMode(i % 2 == 0)
                            3 -> engine.setStereoDownmix(i % 2 == 1)
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting filter threads", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exceptions during concurrent audio filter updates", 0, errors.get())
    }

    // =========================================================================
    // 2. Protected Keys Collision Scenarios
    // =========================================================================

    @Test
    fun testCollision_protectedKeys_uppercaseAndLeadingDashes() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                # Adversarial variations of protected keys
                VO=gpu
                --vo=vdpau
                Vo=opengl
                IDLE=no
                --idle=no
                no-idle
                WID=99999
                --wid=12345
                KEEP-OPEN=no
                --keep-open=no
                no-keep-open
                GPU-CONTEXT=wayland
                --gpu-context=x11
                # Legitimate options that should survive
                demuxer-max-bytes=134217728
                ytdl=yes
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        // 1. Invariant keys must remain locked to engine contracts
        assertEquals("vo must be libmpv", "libmpv", fakeBridge.options["vo"])
        assertEquals("idle must be yes", "yes", fakeBridge.options["idle"])
        assertEquals("keep-open must be yes", "yes", fakeBridge.options["keep-open"])

        // 2. wid and gpu-context must not be injected into options
        assertFalse("wid must not be present in options", fakeBridge.options.containsKey("wid"))
        assertFalse("WID must not be present in options", fakeBridge.options.containsKey("WID"))
        assertFalse("gpu-context must not be present in options", fakeBridge.options.containsKey("gpu-context"))
        assertFalse("GPU-CONTEXT must not be present in options", fakeBridge.options.containsKey("GPU-CONTEXT"))

        // 3. Legitimate options must be injected
        assertEquals("134217728", fakeBridge.options["demuxer-max-bytes"])
        assertEquals("yes", fakeBridge.options["ytdl"])
    }

    @Test
    fun testCollision_protectedKeys_withInlineCommentsAndWhitespace() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                  vo  =  gpu  # override video output
                  idle  =  no # do not stay idle
                  wid  =  777 # native window handle
                  keep-open  =  no # close immediately
                  gpu-context  =  drm # drm context
                  sub-font  =  'Noto Sans' # custom font
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        assertEquals("libmpv", fakeBridge.options["vo"])
        assertEquals("yes", fakeBridge.options["idle"])
        assertEquals("yes", fakeBridge.options["keep-open"])
        assertFalse(fakeBridge.options.containsKey("wid"))
        assertFalse(fakeBridge.options.containsKey("gpu-context"))
        assertEquals("Noto Sans", fakeBridge.options["sub-font"])
    }

    // =========================================================================
    // 3. GUI Settings vs mpv.conf Priority Ordering
    // =========================================================================

    @Test
    fun testPriority_hwdecExplicitGui_overridesMpvConf() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText("hwdec=no\n")
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)
        val settings = UserSettings(defaultHwdec = "mediacodec-copy")

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() }, settings = settings)

        // GUI setting 'mediacodec-copy' must win over mpv.conf 'no'
        assertEquals("mediacodec-copy", fakeBridge.options["hwdec"])
    }

    @Test
    fun testPriority_hwdecDefaultsToHardwareWithCopyFallbackWhenUnset() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText("hwdec=no\n")
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)
        val settings = UserSettings()

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() }, settings = settings)

        // Hardware wins over mpv.conf, with the copy-back variant appended so a codec that refuses
        // the surface still decodes, and with mpv's own software fallback armed for decode errors.
        assertEquals("mediacodec,mediacodec-copy", fakeBridge.options["hwdec"])
        assertEquals("3", fakeBridge.options["hwdec-software-fallback"])
    }

    @Test
    fun testPriority_subAutoExplicitGui_overridesMpvConf() {
        // Test case A: mpv.conf says all, GUI says false -> must result in "no"
        val dirA = tempFolder.newFolder("dirA")
        File(dirA, "mpv.conf").writeText("sub-auto=all\n")
        val ctxA = object : ContextWrapper(null) { override fun getFilesDir() = dirA }
        val mpvRepoA = DefaultMpvConfigRepository(context = ctxA)
        val settingsA = UserSettings(subAutoLoad = false)
        val bridgeA = FakeMpvNativeBridge()

        GyroPlayerEngine(
            context = testContext,
            bridge = bridgeA,
        ).ensureInitialized(userOptions = runBlocking { mpvRepoA.getParsedOptions() }, settings = settingsA)

        assertEquals("no", bridgeA.options["sub-auto"])

        // Test case B: mpv.conf says no, GUI says true -> must result in "all"
        val dirB = tempFolder.newFolder("dirB")
        File(dirB, "mpv.conf").writeText("sub-auto=no\n")
        val ctxB = object : ContextWrapper(null) { override fun getFilesDir() = dirB }
        val mpvRepoB = DefaultMpvConfigRepository(context = ctxB)
        val settingsB = UserSettings(subAutoLoad = true)
        val bridgeB = FakeMpvNativeBridge()

        GyroPlayerEngine(
            context = testContext,
            bridge = bridgeB,
        ).ensureInitialized(userOptions = runBlocking { mpvRepoB.getParsedOptions() }, settings = settingsB)

        assertEquals("all", bridgeB.options["sub-auto"])
    }

    @Test
    fun testPriority_volumeMaxAndAo_alwaysEnforcedOverMpvConf() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                volume-max=100.0
                ao=pulse
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        // Engine requires 200.0 headroom for overdrive and audiotrack,opensles for Android
        assertEquals("200.0", fakeBridge.options["volume-max"])
        assertEquals("audiotrack,opensles", fakeBridge.options["ao"])
    }

    @Test
    fun testPriority_runtimeControls_overridePreInitOptions() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                deband=no
                brightness=50
                contrast=50
                saturation=50
                speed=0.5
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        // Check pre-init options registered
        assertEquals("no", fakeBridge.options["deband"])
        assertEquals("50", fakeBridge.options["brightness"])
        assertEquals("0.5", fakeBridge.options["speed"])

        // Runtime mutations via GUI controls
        engine.setDeband(true)
        assertEquals(true, fakeBridge.properties["deband"])

        engine.resetColors()
        assertEquals(0L, fakeBridge.properties["brightness"])
        assertEquals(0L, fakeBridge.properties["contrast"])
        assertEquals(0L, fakeBridge.properties["saturation"])

        engine.setSpeed(1.25f)
        assertEquals(1.25, fakeBridge.properties["speed"] as Double, 0.001)

        engine.setSoftwareVolume(150.0)
        assertEquals(150.0, fakeBridge.properties["volume"] as Double, 0.001)
        assertTrue((fakeBridge.properties["af"] as String).contains("alimiter"))
    }

    // =========================================================================
    // 4. Rapid Lifecycle Transitions & Surface Attachment/Detachment
    // =========================================================================

    @Test
    fun testLifecycle_reinitializeAfterRelease() {
        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)

        // 1st cycle
        engine.ensureInitialized()
        assertEquals(1, fakeBridge.createCount)
        assertEquals(1, fakeBridge.initCount)

        engine.release()
        assertEquals(1, fakeBridge.destroyCount)
        assertNull(fakeBridge.eventListener)
        assertEquals(PlaybackState.IDLE, engine.playbackState.value)

        // 2nd cycle (re-initialization after release)
        engine.ensureInitialized()
        assertEquals(2, fakeBridge.createCount)
        assertEquals(2, fakeBridge.initCount)
        assertTrue(fakeBridge.eventListener != null)
    }

    private fun createTestSurface(): Surface {
        val texture = android.graphics.SurfaceTexture(0)
        return Surface(texture)
    }

    @Test
    fun testLifecycle_multipleSurfaceAttachmentsAndDetachments() {
        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()

        // Rapid attach / detach sequence
        for (i in 1..20) {
            val surface = createTestSurface()
            engine.attachSurface(surface)
            assertEquals(i, fakeBridge.attachSurfaceCount)

            engine.detachSurface()
            assertEquals(i, fakeBridge.detachSurfaceCount)
        }
    }

    @Test
    fun testLifecycle_loadQueuedUntilSurfaceAttached() {
        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)

        // 1. Load video while surface is NOT attached
        engine.load("video1.mp4", 1000L)
        assertEquals(PlaybackState.BUFFERING, engine.playbackState.value)
        // Command should not have been dispatched to bridge yet
        assertFalse(fakeBridge.hasCommand("loadfile", "video1.mp4"))

        // 2. Load another video while surface still not attached (replaces pending load)
        engine.load("video2.mp4", 2000L)
        assertFalse(fakeBridge.hasCommand("loadfile", "video2.mp4"))

        // 3. Attach surface -> triggers pending load for video2 only
        val surface = createTestSurface()
        engine.attachSurface(surface)

        assertTrue(fakeBridge.hasCommand("loadfile", "video2.mp4", "replace"))
        assertFalse(fakeBridge.hasCommand("loadfile", "video1.mp4", "replace"))
    }

    @Test
    fun testLifecycle_softwareVolumeAutoResetOnTrackChangeAndEndFile() {
        val engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()

        // Set overdrive volume
        engine.setSoftwareVolume(180.0)
        assertEquals(180.0, engine.softwareVolume.value, 0.001)
        assertTrue((fakeBridge.properties["af"] as String).contains("alimiter"))

        // Simulate video load -> auto reset
        engine.load("next_video.mp4")
        assertEquals(100.0, engine.softwareVolume.value, 0.001)
        assertEquals(100.0, fakeBridge.properties["volume"] as Double, 0.001)
        assertFalse((fakeBridge.properties["af"] as String).contains("alimiter"))

        // Set overdrive volume again
        engine.setSoftwareVolume(160.0)
        assertEquals(160.0, engine.softwareVolume.value, 0.001)

        // Simulate END_FILE event -> auto reset
        fakeBridge.emitEndFile()
        assertEquals(100.0, engine.softwareVolume.value, 0.001)
        assertEquals(100.0, fakeBridge.properties["volume"] as Double, 0.001)
        assertFalse((fakeBridge.properties["af"] as String).contains("alimiter"))
    }
}
