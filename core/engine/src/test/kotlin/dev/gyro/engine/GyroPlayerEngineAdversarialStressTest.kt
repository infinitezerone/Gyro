package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Empirical Adversarial & Stress Verification Suite for Milestone 2.
 *
 * Probes:
 * 1. Concurrency stress on [GyroPlayerEngine.updateAudioFilters].
 * 2. Strict filter ordering invariant: pan -> dynaudnorm -> alimiter.
 * 3. Limiter dynamics: injected when volume > 100.0, purged at <= 100.0.
 * 4. Safety auto-reset: resetSoftwareGain across load, release, and END_FILE.
 * 5. Chapter edge cases: negative duration/time, out of bounds seekToChapter, empty chapter list, unlabelled chapters.
 */
class GyroPlayerEngineAdversarialStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var threadSafeBridge: ThreadSafeSpyMpvBridge
    private lateinit var engine: GyroPlayerEngine

    /**
     * Thread-safe spy wrapping FakeMpvNativeBridge with concurrent history recording.
     */
    class ThreadSafeSpyMpvBridge(
        private val delegate: FakeMpvNativeBridge = FakeMpvNativeBridge()
    ) : MpvNativeBridge by delegate {

        private val threadSafeProperties = ConcurrentHashMap<String, Any>()
        val afHistory = CopyOnWriteArrayList<String>()
        val volumeHistory = CopyOnWriteArrayList<Double>()
        val afTransitionsCount = AtomicInteger(0)

        override fun setPropertyString(name: String, value: String) {
            threadSafeProperties[name] = value
            if (name == "af") {
                afHistory.add(value)
                afTransitionsCount.incrementAndGet()
            }
            synchronized(delegate) {
                delegate.setPropertyString(name, value)
            }
        }

        override fun setPropertyDouble(name: String, value: Double) {
            threadSafeProperties[name] = value
            if (name == "volume") {
                volumeHistory.add(value)
            }
            synchronized(delegate) {
                delegate.setPropertyDouble(name, value)
            }
        }

        override fun setPropertyLong(name: String, value: Long) {
            threadSafeProperties[name] = value
            synchronized(delegate) {
                delegate.setPropertyLong(name, value)
            }
        }

        override fun setPropertyBoolean(name: String, value: Boolean) {
            threadSafeProperties[name] = value
            synchronized(delegate) {
                delegate.setPropertyBoolean(name, value)
            }
        }

        override fun getPropertyString(name: String): String? {
            return threadSafeProperties[name]?.toString() ?: synchronized(delegate) { delegate.getPropertyString(name) }
        }

        override fun getPropertyDouble(name: String): Double? {
            val v = threadSafeProperties[name] ?: synchronized(delegate) { delegate.getPropertyDouble(name) } ?: return null
            return when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        }

        override fun getPropertyLong(name: String): Long? {
            val v = threadSafeProperties[name] ?: synchronized(delegate) { delegate.getPropertyLong(name) } ?: return null
            return when (v) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }
        }

        override fun command(cmd: Array<out String>) {
            synchronized(delegate) {
                delegate.command(cmd)
            }
        }

        fun hasRecordedCommand(action: String): Boolean {
            synchronized(delegate) {
                return delegate.recordedCommands.any { it.firstOrNull() == action }
            }
        }

        fun getDelegateBridge(): FakeMpvNativeBridge = delegate
    }

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("engine_adversarial")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        threadSafeBridge = ThreadSafeSpyMpvBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = threadSafeBridge)
        engine.warmUp()
    }

    // =========================================================================
    // 1. Concurrency Stress on updateAudioFilters()
    // =========================================================================

    @Test
    fun testConcurrencyStress_audioFiltersOrderingInvariantUnderParallelMutations() {
        val numThreads = 16
        val operationsPerThread = 250
        val executor = Executors.newFixedThreadPool(numThreads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(numThreads)
        val errors = CopyOnWriteArrayList<Throwable>()

        for (t in 0 until numThreads) {
            executor.execute {
                try {
                    startLatch.await()
                    val rng = Random(t * 1000 + 42)
                    for (i in 0 until operationsPerThread) {
                        when (rng.nextInt(4)) {
                            0 -> {
                                val gain = 100.0 + rng.nextDouble(0.0, 100.0)
                                engine.audio.setSoftwareVolume(gain)
                            }
                            1 -> {
                                engine.audio.resetSoftwareGain()
                            }
                            2 -> {
                                engine.audio.setNightMode(rng.nextBoolean())
                            }
                            3 -> {
                                engine.audio.setStereoDownmix(rng.nextBoolean())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Fire all threads simultaneously
        startLatch.countDown()
        val finished = doneLatch.await(15, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Stress test threads timed out", finished)
        assertTrue("Unexpected exceptions during concurrent mutations: $errors", errors.isEmpty())

        // Verify that in EVERY single observed filter chain snapshot across history,
        // the ordering invariant pan -> dynaudnorm -> alimiter was NEVER violated.
        val recordedHistory = threadSafeBridge.afHistory
        assertTrue("Expected af transitions to be recorded, found none", recordedHistory.isNotEmpty())

        for ((index, af) in recordedHistory.withIndex()) {
            assertFilterOrderingInvariant(af, "Snapshot #$index during concurrency stress")
        }

        // Verify quiescent state consistency
        val finalDownmix = engine.audio.stereoDownmix.value
        val finalNightMode = engine.audio.nightMode.value
        val finalVolume = engine.audio.softwareVolume.value
        val finalAf = threadSafeBridge.getPropertyString("af") ?: ""

        val expectedFilters = mutableListOf<String>()
        if (finalDownmix) expectedFilters.add(dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX)
        if (finalNightMode) expectedFilters.add(dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM)
        if (finalVolume > 100.0) expectedFilters.add(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER)
        val expectedAf = expectedFilters.joinToString(",")

        assertEquals("Quiescent state AF chain mismatch", expectedAf, finalAf)
    }

    // =========================================================================
    // 2. Strict Filter Ordering Invariant (pan -> dynaudnorm -> alimiter)
    // =========================================================================

    @Test
    fun testFilterOrdering_exhaustiveStateSpaceExploration() {
        // 8 combinations: (stereoDownmix: 0..1, nightMode: 0..1, overdrive: 0..1)
        val states = listOf(
            Triple(false, false, 100.0),
            Triple(true, false, 100.0),
            Triple(false, true, 100.0),
            Triple(true, true, 100.0),
            Triple(false, false, 160.0),
            Triple(true, false, 160.0),
            Triple(false, true, 160.0),
            Triple(true, true, 160.0)
        )

        for ((downmix, night, volume) in states) {
            engine.audio.setStereoDownmix(downmix)
            engine.audio.setNightMode(night)
            engine.audio.setSoftwareVolume(volume)

            val af = threadSafeBridge.getPropertyString("af") ?: ""
            assertFilterOrderingInvariant(af, "State ($downmix, $night, $volume)")

            val components = if (af.isEmpty()) emptyList() else af.split(",")
            if (downmix) {
                assertTrue("Missing pan filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX))
            } else {
                assertFalse("Unexpected pan filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX))
            }

            if (night) {
                assertTrue("Missing dynaudnorm filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM))
            } else {
                assertFalse("Unexpected dynaudnorm filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM))
            }

            if (volume > 100.0) {
                assertTrue("Missing alimiter filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER))
            } else {
                assertFalse("Unexpected alimiter filter in state ($downmix, $night, $volume)", components.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER))
            }
        }
    }

    @Test
    fun testFilterOrdering_randomTransitionFuzzing() {
        val rng = Random(1337)
        var currentDownmix = false
        var currentNight = false
        var currentVolume = 100.0

        for (step in 1..500) {
            when (rng.nextInt(3)) {
                0 -> {
                    currentDownmix = rng.nextBoolean()
                    engine.audio.setStereoDownmix(currentDownmix)
                }
                1 -> {
                    currentNight = rng.nextBoolean()
                    engine.audio.setNightMode(currentNight)
                }
                2 -> {
                    currentVolume = if (rng.nextBoolean()) 100.0 else (100.0 + rng.nextDouble(0.1, 100.0))
                    engine.audio.setSoftwareVolume(currentVolume)
                }
            }

            val af = threadSafeBridge.getPropertyString("af") ?: ""
            assertFilterOrderingInvariant(af, "Fuzz step #$step")
        }
    }

    // =========================================================================
    // 3. Limiter Dynamics: Injected at > 100.0, Purged at <= 100.0
    // =========================================================================

    @Test
    fun testLimiterDynamics_exactBoundaryThreshold() {
        // Below boundary: 100.0
        engine.audio.setSoftwareVolume(100.0)
        assertFalse("Limiter should be absent at 100.0", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Sub-100 clamping
        engine.audio.setSoftwareVolume(99.999)
        assertEquals(100.0, engine.audio.softwareVolume.value, 0.0001)
        assertFalse("Limiter should be absent at clamped 100.0", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Just above boundary: 100.00001
        engine.audio.setSoftwareVolume(100.00001)
        assertTrue("Limiter MUST be present at 100.00001", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Boundary toggle back to 100.0
        engine.audio.setSoftwareVolume(100.0)
        assertFalse("Limiter MUST be purged when returning to 100.0", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
        assertEquals("", threadSafeBridge.getPropertyString("af"))

        // Maximum boundary: 200.0
        engine.audio.setSoftwareVolume(200.0)
        assertTrue("Limiter MUST be present at 200.0", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Over-maximum clamping: 200.001 -> clamped to 200.0
        engine.audio.setSoftwareVolume(250.0)
        assertEquals(200.0, engine.audio.softwareVolume.value, 0.0001)
        assertTrue("Limiter MUST be present at clamped 200.0", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testLimiterDynamics_repeatedCycleStress() {
        for (i in 1..50) {
            engine.audio.setSoftwareVolume(100.0 + i * 2.0)
            val activeAf = threadSafeBridge.getPropertyString("af") ?: ""
            assertTrue("Limiter missing at cycle $i", activeAf.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER))

            engine.audio.setSoftwareVolume(100.0)
            val clearedAf = threadSafeBridge.getPropertyString("af") ?: ""
            assertFalse("Limiter not cleared at cycle $i", clearedAf.contains(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER))
            assertEquals("Dangling characters after limiter purge at cycle $i", "", clearedAf)
        }
    }

    @Test
    fun testLimiterDynamics_nanAndInfinityRobustness() {
        // Positive infinity clamps to 200.0
        engine.audio.setSoftwareVolume(Double.POSITIVE_INFINITY)
        assertEquals(200.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Negative infinity clamps to 100.0
        engine.audio.setSoftwareVolume(Double.NEGATIVE_INFINITY)
        assertEquals(100.0, engine.audio.softwareVolume.value, 0.001)
        assertFalse(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Double.NaN input should not crash engine
        engine.audio.setSoftwareVolume(Double.NaN)
        // NaN.coerceIn returns NaN in Kotlin standard library
        val current = engine.audio.softwareVolume.value
        // Verify engine survives and limiter logic handles current state without throwing
        assertFalse("Limiter must not be active for NaN", current > 100.0 && threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Can recover safely to 100.0
        engine.audio.resetSoftwareGain()
        assertEquals(100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("", threadSafeBridge.getPropertyString("af"))
    }

    // =========================================================================
    // 4. Safety Auto-Reset across load, release, END_FILE, SHUTDOWN
    // =========================================================================

    @Test
    fun testSafetyAutoReset_acrossLoad() {
        // Arm overdrive with active limiter
        engine.audio.setSoftwareVolume(175.0)
        assertEquals(175.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Trigger load
        engine.load("http://test.server/stream.mp4")

        // Assert auto-reset
        assertEquals("softwareVolume must reset to 100.0 on load()", 100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("mpv volume must reset to 100.0 on load()", 100.0, threadSafeBridge.getPropertyDouble("volume") ?: 0.0, 0.001)
        assertFalse("alimiter must be purged on load()", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testSafetyAutoReset_acrossLoadUri() {
        // Arm overdrive with active limiter
        engine.audio.setSoftwareVolume(185.0)
        assertEquals(185.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Trigger load
        val testUri = TestUri("content://media/external/video/media/42")
        engine.load(testUri)

        // Assert auto-reset
        assertEquals("softwareVolume must reset to 100.0 on load()", 100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("mpv volume must reset to 100.0 on load()", 100.0, threadSafeBridge.getPropertyDouble("volume") ?: 0.0, 0.001)
        assertFalse("alimiter must be purged on load()", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testSafetyAutoReset_acrossRelease() {
        // Arm overdrive with active limiter
        engine.audio.setSoftwareVolume(190.0)
        assertEquals(190.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Trigger release
        engine.release()

        // Assert auto-reset
        assertEquals("softwareVolume must reset to 100.0 on release()", 100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("mpv volume must reset to 100.0 on release()", 100.0, threadSafeBridge.getPropertyDouble("volume") ?: 0.0, 0.001)
        assertFalse("alimiter must be purged on release()", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testSafetyAutoReset_acrossEndFileEvent() {
        // Arm overdrive with active limiter
        engine.audio.setSoftwareVolume(165.0)
        assertEquals(165.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Trigger END_FILE
        threadSafeBridge.getDelegateBridge().emitEndFile()

        // Assert auto-reset
        assertEquals("softwareVolume must reset to 100.0 on END_FILE", 100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("mpv volume must reset to 100.0 on END_FILE", 100.0, threadSafeBridge.getPropertyDouble("volume") ?: 0.0, 0.001)
        assertFalse("alimiter must be purged on END_FILE", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testSafetyAutoReset_acrossShutdownEvent() {
        // Arm overdrive with active limiter
        engine.audio.setSoftwareVolume(145.0)
        assertEquals(145.0, engine.audio.softwareVolume.value, 0.001)
        assertTrue(threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)

        // Trigger SHUTDOWN
        threadSafeBridge.getDelegateBridge().emitShutdown()

        // Assert auto-reset
        assertEquals("softwareVolume must reset to 100.0 on SHUTDOWN", 100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals("mpv volume must reset to 100.0 on SHUTDOWN", 100.0, threadSafeBridge.getPropertyDouble("volume") ?: 0.0, 0.001)
        assertFalse("alimiter must be purged on SHUTDOWN", threadSafeBridge.getPropertyString("af")?.contains("alimiter") == true)
    }

    @Test
    fun testSafetyAutoReset_preservesConcurrentNightModeAndDownmix() {
        // Enable persistent filters
        engine.audio.setNightMode(true)
        engine.audio.setStereoDownmix(true)
        engine.audio.setSoftwareVolume(170.0)

        val fullChain = "${dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX},${dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM},${dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(fullChain, threadSafeBridge.getPropertyString("af"))

        // Trigger safety reset
        engine.audio.resetSoftwareGain()

        // Verify limiter purged, but user nightMode and downmix filters strictly preserved!
        val remainingChain = "${dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX},${dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM}"
        assertEquals(remainingChain, threadSafeBridge.getPropertyString("af"))
        assertTrue(engine.audio.nightMode.value)
        assertTrue(engine.audio.stereoDownmix.value)
    }

    // =========================================================================
    // 5. Chapter Edge Cases
    // =========================================================================

    @Test
    fun testChapters_negativeTimesAndDurationsCoercedToZero() {
        threadSafeBridge.setPropertyLong("chapter-list/count", 3L)
        threadSafeBridge.setPropertyString("chapter-list/0/title", "Negative Time Ch1")
        threadSafeBridge.setPropertyDouble("chapter-list/0/time", -12.5)

        threadSafeBridge.setPropertyString("chapter-list/1/title", "Negative Small Time Ch2")
        threadSafeBridge.setPropertyDouble("chapter-list/1/time", -0.0001)

        threadSafeBridge.setPropertyString("chapter-list/2/title", "Normal Ch3")
        threadSafeBridge.setPropertyDouble("chapter-list/2/time", 45.0)

        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")

        val chapters = engine.trackManager.chapters.value
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].timeMs)
        assertEquals(0L, chapters[1].timeMs)
        assertEquals(45000L, chapters[2].timeMs)
    }

    @Test
    fun testChapters_extremeDoubleValuesHandledSafely() {
        threadSafeBridge.setPropertyLong("chapter-list/count", 3L)
        threadSafeBridge.setPropertyString("chapter-list/0/title", "Neg Inf")
        threadSafeBridge.setPropertyDouble("chapter-list/0/time", Double.NEGATIVE_INFINITY)

        threadSafeBridge.setPropertyString("chapter-list/1/title", "NaN Time")
        threadSafeBridge.setPropertyDouble("chapter-list/1/time", Double.NaN)

        threadSafeBridge.setPropertyString("chapter-list/2/title", "Pos Inf")
        threadSafeBridge.setPropertyDouble("chapter-list/2/time", Double.POSITIVE_INFINITY)

        // Must not throw arithmetic exceptions
        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")

        val chapters = engine.trackManager.chapters.value
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].timeMs)
        assertEquals(0L, chapters[1].timeMs)
        assertEquals(Long.MAX_VALUE, chapters[2].timeMs)
    }

    @Test
    fun testChapters_outOfBoundsSeekDoesNotCrashOrMutateMpvProperty() {
        threadSafeBridge.setPropertyLong("chapter-list/count", 2L)
        threadSafeBridge.setPropertyString("chapter-list/0/title", "Ch0")
        threadSafeBridge.setPropertyDouble("chapter-list/0/time", 0.0)
        threadSafeBridge.setPropertyString("chapter-list/1/title", "Ch1")
        threadSafeBridge.setPropertyDouble("chapter-list/1/time", 60.0)
        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")
        threadSafeBridge.getDelegateBridge().emitFileLoaded("Test")

        threadSafeBridge.getDelegateBridge().emitPropertyLong("chapter", 0L)

        // Adversarial out of bounds attempts
        val invalidIndices = listOf(-100, -1, 2, 3, 999, Int.MIN_VALUE, Int.MAX_VALUE)
        for (invalid in invalidIndices) {
            engine.seekToChapter(invalid)
            assertEquals("Chapter property should not mutate on invalid seek index $invalid", 0L, threadSafeBridge.getPropertyLong("chapter"))
            assertEquals("Engine currentChapter state should not change on invalid seek index $invalid", 0, engine.trackManager.currentChapter.value)
        }
    }

    @Test
    fun testChapters_emptyChapterListAndNegativeCount() {
        // Zero count
        threadSafeBridge.setPropertyLong("chapter-list/count", 0L)
        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")
        assertTrue("Chapters should be empty on count=0", engine.trackManager.chapters.value.isEmpty())
        assertEquals(-1, engine.trackManager.currentChapter.value)

        // Out of bounds seek on empty list
        engine.seekToChapter(0)
        assertFalse(threadSafeBridge.getDelegateBridge().properties.containsKey("chapter"))

        // Negative count
        threadSafeBridge.setPropertyLong("chapter-list/count", -5L)
        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")
        assertTrue("Chapters should be empty on count < 0", engine.trackManager.chapters.value.isEmpty())
        assertEquals(-1, engine.trackManager.currentChapter.value)
    }

    @Test
    fun testChapters_unlabelledAndWhitespaceTitles() {
        threadSafeBridge.setPropertyLong("chapter-list/count", 5L)
        threadSafeBridge.setPropertyString("chapter-list/0/title", "") // empty
        threadSafeBridge.setPropertyDouble("chapter-list/0/time", 0.0)

        // title omitted (null)
        threadSafeBridge.setPropertyDouble("chapter-list/1/time", 10.0)

        threadSafeBridge.setPropertyString("chapter-list/2/title", "   \t\n  ") // whitespace
        threadSafeBridge.setPropertyDouble("chapter-list/2/time", 20.0)

        threadSafeBridge.setPropertyString("chapter-list/3/title", "  Trimmed Intro  ")
        threadSafeBridge.setPropertyDouble("chapter-list/3/time", 30.0)

        threadSafeBridge.setPropertyString("chapter-list/4/title", "第五章 終幕 🌸 (Final)")
        threadSafeBridge.setPropertyDouble("chapter-list/4/time", 40.0)

        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")

        val chapters = engine.trackManager.chapters.value
        assertEquals(5, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
        assertEquals("Chapter 3", chapters[2].title)
        assertEquals("Trimmed Intro", chapters[3].title)
        assertEquals("第五章 終幕 🌸 (Final)", chapters[4].title)
    }

    @Test
    fun testChapters_pendingSeekBeforeFileLoaded() {
        threadSafeBridge.setPropertyLong("chapter-list/count", 2L)
        threadSafeBridge.setPropertyString("chapter-list/0/title", "Start")
        threadSafeBridge.setPropertyDouble("chapter-list/0/time", 0.0)
        threadSafeBridge.setPropertyString("chapter-list/1/title", "Middle")
        threadSafeBridge.setPropertyDouble("chapter-list/1/time", 90.0)
        threadSafeBridge.getDelegateBridge().emitProperty("chapter-list")

        // Seek before fileLoaded is emitted
        engine.seekToChapter(1)

        // Notice: native seek command not dispatched yet because file not loaded
        assertFalse(threadSafeBridge.hasRecordedCommand("seek"))

        // Emit file loaded -> pending seek executes!
        threadSafeBridge.getDelegateBridge().emitFileLoaded("Pending Test")
        assertTrue(threadSafeBridge.hasRecordedCommand("seek"))
        val lastCmd = threadSafeBridge.getDelegateBridge().lastCommand()
        assertEquals(listOf("seek", "90.0", "absolute"), lastCmd)
    }

    // =========================================================================
    // Invariant Verification Helper
    // =========================================================================

    private fun assertFilterOrderingInvariant(af: String, context: String) {
        if (af.isEmpty()) return

        val filters = af.split(",")
        // 1. Verify no empty tokens (e.g., ",filter" or "filter1,,filter2")
        for (token in filters) {
            assertTrue("Malformed empty filter token in '$af' ($context)", token.isNotBlank())
        }

        // 2. Verify all tokens are recognized filters
        val validFilters = setOf(
            dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX,
            dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM,
            dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER
        )
        for (token in filters) {
            assertTrue("Unrecognized filter token '$token' in '$af' ($context)", validFilters.contains(token))
        }

        // 3. Verify no duplicates
        val distinctFilters = filters.distinct()
        assertEquals("Duplicate filters detected in '$af' ($context)", distinctFilters.size, filters.size)

        // 4. Verify relative ordering invariant: pan < dynaudnorm < alimiter
        val downmixIdx = filters.indexOf(dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX)
        val dynaudnormIdx = filters.indexOf(dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM)
        val alimiterIdx = filters.indexOf(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER)

        if (downmixIdx != -1 && dynaudnormIdx != -1) {
            assertTrue(
                "Filter invariant violation: pan (idx $downmixIdx) must precede dynaudnorm (idx $dynaudnormIdx) in '$af' ($context)",
                downmixIdx < dynaudnormIdx
            )
        }

        if (downmixIdx != -1 && alimiterIdx != -1) {
            assertTrue(
                "Filter invariant violation: pan (idx $downmixIdx) must precede alimiter (idx $alimiterIdx) in '$af' ($context)",
                downmixIdx < alimiterIdx
            )
        }

        if (dynaudnormIdx != -1 && alimiterIdx != -1) {
            assertTrue(
                "Filter invariant violation: dynaudnorm (idx $dynaudnormIdx) must precede alimiter (idx $alimiterIdx) in '$af' ($context)",
                dynaudnormIdx < alimiterIdx
            )
        }
    }
}
