package dev.gyro.core.testing.fakes

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.view.Surface
import dev.gyro.engine.MpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit test suite for [FakeMpvNativeBridge].
 *
 * Verifies:
 * 1. 16-thread multi-threaded concurrency stress testing with 0 commands or counters dropped
 * 2. Lifecycle atomic counters (`createCount`, `initCount`, `destroyCount`, `attachSurfaceCount`, `detachSurfaceCount`)
 * 3. Property stores, getters/setters, type conversions, nullability, and options handling
 * 4. Event and property change simulation helpers with registered and null listeners
 * 5. Query helpers (`hasCommand`, `lastCommand`, `findCommands`) and thread-safe [FakeMpvNativeBridge.reset]
 */
class FakeMpvNativeBridgeTest {

    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var testContext: Context

    @Before
    fun setUp() {
        fakeBridge = FakeMpvNativeBridge()
        testContext = object : ContextWrapper(null) {}
    }

    private fun createTestSurface(): Surface {
        val texture = SurfaceTexture(0)
        return Surface(texture)
    }

    // =========================================================================
    // 1. Initial State & Lifecycle Atomic Counters
    // =========================================================================

    @Test
    fun testInitialState_allCountersAreZeroAndCollectionsEmpty() {
        assertEquals("Initial createCount must be 0", 0, fakeBridge.createCount)
        assertEquals("Initial initCount must be 0", 0, fakeBridge.initCount)
        assertEquals("Initial destroyCount must be 0", 0, fakeBridge.destroyCount)
        assertEquals("Initial attachSurfaceCount must be 0", 0, fakeBridge.attachSurfaceCount)
        assertEquals("Initial detachSurfaceCount must be 0", 0, fakeBridge.detachSurfaceCount)

        assertTrue("Initial commands must be empty", fakeBridge.commands.isEmpty())
        assertTrue("Initial recordedCommands must be empty", fakeBridge.recordedCommands.isEmpty())
        assertTrue("Initial options must be empty", fakeBridge.options.isEmpty())
        assertTrue("Initial recordedOptions must be empty", fakeBridge.recordedOptions.isEmpty())
        assertTrue("Initial properties must be empty", fakeBridge.properties.isEmpty())
        assertTrue("Initial recordedProperties must be empty", fakeBridge.recordedProperties.isEmpty())
        assertTrue("Initial observedProperties must be empty", fakeBridge.observedProperties.isEmpty())
        assertTrue("Initial attachedSurfaces must be empty", fakeBridge.attachedSurfaces.isEmpty())

        assertNull("Initial eventListener must be null", fakeBridge.eventListener)
        assertTrue("Initial isZeroCopySafe must be true", fakeBridge.isZeroCopySafe())
        assertEquals("Initial getGpuRenderer default", "Adreno (TM) 750", fakeBridge.getGpuRenderer())
    }

    @Test
    fun testLifecycleCounters_sequentialInvocationsIncrementIndependently() {
        fakeBridge.create(testContext)
        fakeBridge.create(testContext)
        fakeBridge.create(testContext)
        assertEquals("createCount must be 3", 3, fakeBridge.createCount)
        assertEquals("initCount must remain 0", 0, fakeBridge.initCount)
        assertEquals("destroyCount must remain 0", 0, fakeBridge.destroyCount)

        fakeBridge.init()
        fakeBridge.init()
        assertEquals("initCount must be 2", 2, fakeBridge.initCount)
        assertEquals("createCount must still be 3", 3, fakeBridge.createCount)

        fakeBridge.destroy()
        assertEquals("destroyCount must be 1", 1, fakeBridge.destroyCount)
        assertEquals("createCount must still be 3", 3, fakeBridge.createCount)
        assertEquals("initCount must still be 2", 2, fakeBridge.initCount)
    }

    @Test
    fun testSurfaceAttachment_tracksCountAndSurfaces() {
        val surface1 = createTestSurface()
        val surface2 = createTestSurface()
        val surface3 = createTestSurface()

        fakeBridge.attachSurface(surface1)
        assertEquals("attachSurfaceCount must be 1", 1, fakeBridge.attachSurfaceCount)
        assertEquals(1, fakeBridge.attachedSurfaces.size)
        assertEquals(surface1, fakeBridge.attachedSurfaces[0])

        fakeBridge.detachSurface()
        assertEquals("detachSurfaceCount must be 1", 1, fakeBridge.detachSurfaceCount)

        fakeBridge.attachSurface(surface2)
        fakeBridge.attachSurface(surface3)
        assertEquals("attachSurfaceCount must be 3", 3, fakeBridge.attachSurfaceCount)
        assertEquals(3, fakeBridge.attachedSurfaces.size)
        assertEquals(surface2, fakeBridge.attachedSurfaces[1])
        assertEquals(surface3, fakeBridge.attachedSurfaces[2])

        fakeBridge.detachSurface()
        assertEquals("detachSurfaceCount must be 2", 2, fakeBridge.detachSurfaceCount)
    }

    // =========================================================================
    // 2. Property Stores, Getters/Setters, Type Conversions & Nullability
    // =========================================================================

    @Test
    fun testPropertyGetters_unsetPropertiesReturnNull() {
        assertNull(fakeBridge.getPropertyString("unknown_prop"))
        assertNull(fakeBridge.getPropertyLong("unknown_prop"))
        assertNull(fakeBridge.getPropertyDouble("unknown_prop"))
        assertNull(fakeBridge.getPropertyBoolean("unknown_prop"))
        assertNull(fakeBridge.getLong("unknown_prop"))
        assertNull(fakeBridge.getDouble("unknown_prop"))
        assertNull(fakeBridge.getBoolean("unknown_prop"))
    }

    @Test
    fun testPropertySettersAndGetters_string() {
        fakeBridge.setPropertyString("media-title", "Test Video Title")
        assertEquals("Test Video Title", fakeBridge.getPropertyString("media-title"))
        assertEquals("Test Video Title", fakeBridge.properties["media-title"])
        assertEquals("Test Video Title", fakeBridge.recordedProperties["media-title"])
    }

    @Test
    fun testPropertyTypeConversions_long() {
        // Direct Long
        fakeBridge.setPropertyLong("time-pos", 123456789L)
        assertEquals(123456789L, fakeBridge.getPropertyLong("time-pos"))
        assertEquals(123456789L, fakeBridge.getLong("time-pos"))
        assertEquals("123456789", fakeBridge.getPropertyString("time-pos"))

        // Int Number conversion
        fakeBridge.setProperty("frame-number", 42)
        assertEquals(42L, fakeBridge.getPropertyLong("frame-number"))

        // Double Number conversion (truncation)
        fakeBridge.setPropertyDouble("duration", 99.8)
        assertEquals(99L, fakeBridge.getPropertyLong("duration"))

        // Numeric String conversion
        fakeBridge.setPropertyString("bitrate", "5000000")
        assertEquals(5000000L, fakeBridge.getPropertyLong("bitrate"))

        // Non-numeric String conversion -> null
        fakeBridge.setPropertyString("codec", "h264")
        assertNull(fakeBridge.getPropertyLong("codec"))

        // Boolean -> null
        fakeBridge.setPropertyBoolean("flag", true)
        assertNull(fakeBridge.getPropertyLong("flag"))
    }

    @Test
    fun testPropertyTypeConversions_double() {
        // Direct Double
        fakeBridge.setPropertyDouble("speed", 1.5)
        assertEquals(1.5, fakeBridge.getPropertyDouble("speed")!!, 0.0001)
        assertEquals(1.5, fakeBridge.getDouble("speed")!!, 0.0001)

        // Float Number conversion
        fakeBridge.setProperty("fps", 29.97f)
        assertEquals(29.97, fakeBridge.getPropertyDouble("fps")!!, 0.001)

        // Long Number conversion
        fakeBridge.setPropertyLong("cache-bytes", 1024L)
        assertEquals(1024.0, fakeBridge.getPropertyDouble("cache-bytes")!!, 0.0001)

        // Numeric String conversion
        fakeBridge.setPropertyString("aspect", "1.7777")
        assertEquals(1.7777, fakeBridge.getPropertyDouble("aspect")!!, 0.0001)

        // Non-numeric String conversion -> null
        fakeBridge.setPropertyString("title", "invalid_double")
        assertNull(fakeBridge.getPropertyDouble("title"))

        // Boolean -> null
        fakeBridge.setPropertyBoolean("flag", false)
        assertNull(fakeBridge.getPropertyDouble("flag"))
    }

    @Test
    fun testPropertyTypeConversions_boolean() {
        // Direct Boolean
        fakeBridge.setPropertyBoolean("pause", true)
        assertEquals(true, fakeBridge.getPropertyBoolean("pause"))
        assertEquals(true, fakeBridge.getBoolean("pause"))

        fakeBridge.setPropertyBoolean("mute", false)
        assertEquals(false, fakeBridge.getPropertyBoolean("mute"))
        assertEquals(false, fakeBridge.getBoolean("mute"))

        // Number: 0 is false, non-zero is true
        fakeBridge.setProperty("num_zero", 0)
        assertEquals(false, fakeBridge.getPropertyBoolean("num_zero"))

        fakeBridge.setProperty("num_one", 1)
        assertEquals(true, fakeBridge.getPropertyBoolean("num_one"))

        fakeBridge.setProperty("num_neg", -1)
        assertEquals(true, fakeBridge.getPropertyBoolean("num_neg"))

        fakeBridge.setPropertyLong("num_large", 100L)
        assertEquals(true, fakeBridge.getPropertyBoolean("num_large"))

        // String: "yes"/"true" (case-insensitive) -> true
        fakeBridge.setPropertyString("str_yes", "yes")
        assertEquals(true, fakeBridge.getPropertyBoolean("str_yes"))

        fakeBridge.setPropertyString("str_yes_upper", "YES")
        assertEquals(true, fakeBridge.getPropertyBoolean("str_yes_upper"))

        fakeBridge.setPropertyString("str_true", "true")
        assertEquals(true, fakeBridge.getPropertyBoolean("str_true"))

        fakeBridge.setPropertyString("str_true_upper", "TRUE")
        assertEquals(true, fakeBridge.getPropertyBoolean("str_true_upper"))

        // String: "no"/"false" -> false
        fakeBridge.setPropertyString("str_no", "no")
        assertEquals(false, fakeBridge.getPropertyBoolean("str_no"))

        fakeBridge.setPropertyString("str_false", "false")
        assertEquals(false, fakeBridge.getPropertyBoolean("str_false"))

        // Incompatible String -> null or false
        fakeBridge.setPropertyString("str_random", "random_string")
        val boolResult = fakeBridge.getPropertyBoolean("str_random")
        assertTrue(boolResult == null || boolResult == false)

        // Incompatible Object -> null
        fakeBridge.setProperty("obj_prop", Any())
        assertNull(fakeBridge.getPropertyBoolean("obj_prop"))
    }

    @Test
    fun testPropertyNullability_setPropertyNullSafelyRemovesEntry() {
        fakeBridge.setProperty("nullable_key", "initial_value")
        assertEquals("initial_value", fakeBridge.getPropertyString("nullable_key"))

        // Passing null must safely remove key without NullPointerException in ConcurrentHashMap
        fakeBridge.setProperty("nullable_key", null)
        assertNull(fakeBridge.getPropertyString("nullable_key"))
        assertFalse(fakeBridge.properties.containsKey("nullable_key"))
    }

    @Test
    fun testPropertyNullability_propertiesMapDirectNullAssignment() {
        fakeBridge.properties["null_prop"] = null
        assertNull(fakeBridge.getPropertyString("null_prop"))
        assertNull(fakeBridge.properties["null_prop"])
    }

    @Test
    fun testOptions_setOptionStringAndRecording() {
        val rc1 = fakeBridge.setOptionString("hwdec", "mediacodec")
        val rc2 = fakeBridge.setOptionString("vo", "libmpv")

        assertEquals("setOptionString must return 0 on success", 0, rc1)
        assertEquals("setOptionString must return 0 on success", 0, rc2)
        assertEquals("mediacodec", fakeBridge.options["hwdec"])
        assertEquals("libmpv", fakeBridge.options["vo"])
        assertEquals("mediacodec", fakeBridge.recordedOptions["hwdec"])
    }

    // =========================================================================
    // 3. Event & Property Simulation Helpers
    // =========================================================================

    private class RecordingEventListener : MpvNativeBridge.EventListener {
        val events = CopyOnWriteArrayList<Int>()
        val propChanges = CopyOnWriteArrayList<String>()
        val propLongs = CopyOnWriteArrayList<Pair<String, Long>>()
        val propDoubles = CopyOnWriteArrayList<Pair<String, Double>>()
        val propFlags = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val propStrings = CopyOnWriteArrayList<Pair<String, String>>()

        override fun onEvent(eventId: Int) {
            events.add(eventId)
        }

        override fun onProperty(name: String) {
            propChanges.add(name)
        }

        override fun onPropertyLong(name: String, value: Long) {
            propLongs.add(name to value)
        }

        override fun onPropertyDouble(name: String, value: Double) {
            propDoubles.add(name to value)
        }

        override fun onPropertyFlag(name: String, value: Boolean) {
            propFlags.add(name to value)
        }

        override fun onPropertyString(name: String, value: String) {
            propStrings.add(name to value)
        }
    }

    @Test
    fun testEventSimulation_dispatchesToRegisteredListener() {
        val listener = RecordingEventListener()
        fakeBridge.eventListener = listener

        fakeBridge.emitEvent(MpvNativeBridge.Event.PLAYBACK_RESTART)
        fakeBridge.emitTracksChanged()
        fakeBridge.emitEndFile()
        fakeBridge.emitShutdown()

        assertEquals(4, listener.events.size)
        assertEquals(MpvNativeBridge.Event.PLAYBACK_RESTART, listener.events[0])
        assertEquals(MpvNativeBridge.Event.TRACKS_CHANGED, listener.events[1])
        assertEquals(MpvNativeBridge.Event.END_FILE, listener.events[2])
        assertEquals(MpvNativeBridge.Event.SHUTDOWN, listener.events[3])
    }

    @Test
    fun testEventSimulation_nullListenerDoesNotThrow() {
        fakeBridge.eventListener = null
        // None of these should throw NullPointerException
        fakeBridge.emitEvent(MpvNativeBridge.Event.PLAYBACK_RESTART)
        fakeBridge.emitTracksChanged()
        fakeBridge.emitEndFile()
        fakeBridge.emitShutdown()
        fakeBridge.emitFileLoaded()
        fakeBridge.emitProperty("test")
        fakeBridge.emitPropertyLong("test", 1L)
        fakeBridge.emitPropertyDouble("test", 1.0)
        fakeBridge.emitPropertyFlag("test", true)
        fakeBridge.emitPropertyString("test", "val")
    }

    @Test
    fun testEmitFileLoaded_updatesPropertiesAndFiresEvent() {
        val listener = RecordingEventListener()
        fakeBridge.eventListener = listener

        // Default parameters
        fakeBridge.emitFileLoaded()
        assertEquals("Test Video", fakeBridge.getPropertyString("media-title"))
        assertEquals("mediacodec", fakeBridge.getPropertyString("hwdec-current"))
        assertEquals(1, listener.events.size)
        assertEquals(MpvNativeBridge.Event.FILE_LOADED, listener.events[0])

        // Custom parameters
        fakeBridge.emitFileLoaded(mediaTitle = "4K HDR Video", hwdec = "mediacodec-copy")
        assertEquals("4K HDR Video", fakeBridge.getPropertyString("media-title"))
        assertEquals("mediacodec-copy", fakeBridge.getPropertyString("hwdec-current"))
        assertEquals(2, listener.events.size)
        assertEquals(MpvNativeBridge.Event.FILE_LOADED, listener.events[1])
    }

    @Test
    fun testEmitPropertyChanges_updatesStoreAndDispatches() {
        val listener = RecordingEventListener()
        fakeBridge.eventListener = listener

        fakeBridge.emitProperty("pause")
        assertEquals(listOf("pause"), listener.propChanges)

        fakeBridge.emitPropertyLong("time-pos", 12345L)
        assertEquals(12345L, fakeBridge.getPropertyLong("time-pos"))
        assertEquals(listOf("time-pos" to 12345L), listener.propLongs)

        fakeBridge.emitPropertyDouble("speed", 1.75)
        assertEquals(1.75, fakeBridge.getPropertyDouble("speed")!!, 0.001)
        assertEquals(listOf("speed" to 1.75), listener.propDoubles)

        fakeBridge.emitPropertyFlag("eof-reached", true)
        assertEquals(true, fakeBridge.getPropertyBoolean("eof-reached"))
        assertEquals(listOf("eof-reached" to true), listener.propFlags)

        fakeBridge.emitPropertyString("audio-codec-name", "aac")
        assertEquals("aac", fakeBridge.getPropertyString("audio-codec-name"))
        assertEquals(listOf("audio-codec-name" to "aac"), listener.propStrings)
    }

    @Test
    fun testPropertyObservation_tracksFormatAndQueries() {
        fakeBridge.observeProperty("time-pos", MpvNativeBridge.Format.INT64)
        fakeBridge.observeProperty("pause", MpvNativeBridge.Format.FLAG)

        assertTrue("time-pos must be observed", fakeBridge.isPropertyObserved("time-pos"))
        assertTrue("time-pos must match INT64 format", fakeBridge.isPropertyObserved("time-pos", MpvNativeBridge.Format.INT64))
        assertFalse("time-pos must not match DOUBLE format", fakeBridge.isPropertyObserved("time-pos", MpvNativeBridge.Format.DOUBLE))

        assertTrue("pause must be observed", fakeBridge.isPropertyObserved("pause"))
        assertTrue("pause must match FLAG format", fakeBridge.isPropertyObserved("pause", MpvNativeBridge.Format.FLAG))

        assertFalse("unregistered prop must not be observed", fakeBridge.isPropertyObserved("speed"))
        assertFalse("unregistered prop must not be observed with format", fakeBridge.isPropertyObserved("speed", MpvNativeBridge.Format.NONE))
    }

    @Test
    fun testDriverProbeDiagnostics_configurable() {
        assertTrue(fakeBridge.isZeroCopySafe())
        assertEquals("Adreno (TM) 750", fakeBridge.getGpuRenderer())

        fakeBridge.isZeroCopySafeResult = false
        fakeBridge.gpuRendererResult = "Mali-G78 MP14"

        assertFalse(fakeBridge.isZeroCopySafe())
        assertEquals("Mali-G78 MP14", fakeBridge.getGpuRenderer())
    }

    // =========================================================================
    // 4. Query Helpers & Reset
    // =========================================================================

    @Test
    fun testCommandRecording_andQueryHelpers() {
        assertTrue(fakeBridge.commands.isEmpty())
        assertNull(fakeBridge.lastCommand())
        assertFalse(fakeBridge.hasCommand("seek"))

        fakeBridge.command(arrayOf("loadfile", "video.mp4", "replace"))
        fakeBridge.command(arrayOf("seek", "30", "absolute"))
        fakeBridge.command(arrayOf("set", "pause", "yes"))
        fakeBridge.command(arrayOf("seek", "60", "relative"))

        assertEquals(4, fakeBridge.commands.size)
        assertEquals(listOf("seek", "60", "relative"), fakeBridge.lastCommand())

        // hasCommand prefix tests
        assertTrue(fakeBridge.hasCommand("loadfile"))
        assertTrue(fakeBridge.hasCommand("loadfile", "video.mp4"))
        assertTrue(fakeBridge.hasCommand("loadfile", "video.mp4", "replace"))
        assertFalse(fakeBridge.hasCommand("loadfile", "other.mp4"))
        assertFalse(fakeBridge.hasCommand("loadfile", "video.mp4", "replace", "overflow"))
        assertTrue(fakeBridge.hasCommand("seek"))
        assertTrue(fakeBridge.hasCommand("set", "pause"))
        assertFalse(fakeBridge.hasCommand("stop"))

        // findCommands action filter tests
        val seekCommands = fakeBridge.findCommands("seek")
        assertEquals(2, seekCommands.size)
        assertEquals(listOf("seek", "30", "absolute"), seekCommands[0])
        assertEquals(listOf("seek", "60", "relative"), seekCommands[1])

        val loadCommands = fakeBridge.findCommands("loadfile")
        assertEquals(1, loadCommands.size)
        assertEquals(listOf("loadfile", "video.mp4", "replace"), loadCommands[0])

        val unknownCommands = fakeBridge.findCommands("unknown_action")
        assertTrue(unknownCommands.isEmpty())
    }

    @Test
    fun testReset_clearsAllStateAndCounters() {
        // Populate state
        fakeBridge.create(testContext)
        fakeBridge.init()
        fakeBridge.destroy()
        val surface = createTestSurface()
        fakeBridge.attachSurface(surface)
        fakeBridge.detachSurface()

        fakeBridge.command(arrayOf("loadfile", "video.mp4"))
        fakeBridge.setOptionString("hwdec", "mediacodec")
        fakeBridge.setPropertyString("prop1", "val1")
        fakeBridge.observeProperty("obs1", MpvNativeBridge.Format.STRING)

        // Verify pre-conditions
        assertEquals(1, fakeBridge.createCount)
        assertEquals(1, fakeBridge.initCount)
        assertEquals(1, fakeBridge.destroyCount)
        assertEquals(1, fakeBridge.attachSurfaceCount)
        assertEquals(1, fakeBridge.detachSurfaceCount)
        assertFalse(fakeBridge.commands.isEmpty())
        assertFalse(fakeBridge.options.isEmpty())
        assertFalse(fakeBridge.properties.isEmpty())
        assertFalse(fakeBridge.observedProperties.isEmpty())
        assertFalse(fakeBridge.attachedSurfaces.isEmpty())

        // Execute reset
        fakeBridge.reset()

        // Verify all counters reset to 0
        assertEquals(0, fakeBridge.createCount)
        assertEquals(0, fakeBridge.initCount)
        assertEquals(0, fakeBridge.destroyCount)
        assertEquals(0, fakeBridge.attachSurfaceCount)
        assertEquals(0, fakeBridge.detachSurfaceCount)

        // Verify all collections cleared
        assertTrue(fakeBridge.commands.isEmpty())
        assertTrue(fakeBridge.recordedCommands.isEmpty())
        assertTrue(fakeBridge.options.isEmpty())
        assertTrue(fakeBridge.recordedOptions.isEmpty())
        assertTrue(fakeBridge.properties.isEmpty())
        assertTrue(fakeBridge.recordedProperties.isEmpty())
        assertTrue(fakeBridge.observedProperties.isEmpty())
        assertTrue(fakeBridge.attachedSurfaces.isEmpty())

        // Verify query helpers reflect empty state
        assertNull(fakeBridge.lastCommand())
        assertFalse(fakeBridge.hasCommand("loadfile"))
        assertTrue(fakeBridge.findCommands("loadfile").isEmpty())
    }

    // =========================================================================
    // 5. 16-Thread Multi-Threaded Concurrency Stress Tests
    // =========================================================================

    @Test
    fun testConcurrency_16Threads_concurrentCommands_zeroDropped() {
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
            "FakeMpvNativeBridge must record all commands without loss under 16-thread concurrency",
            totalExpected,
            recordedCount
        )
        assertEquals("recordedCommands property must match commands size", totalExpected, fakeBridge.recordedCommands.size)
        assertTrue("hasCommand query must find concurrent commands", fakeBridge.hasCommand("seek"))
    }

    @Test
    fun testConcurrency_16Threads_concurrentSurfaceAttachmentAndCounters_zeroDropped() {
        val threadCount = 16
        val iterationsPerThread = 50
        val totalExpected = threadCount * iterationsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)
        val surface = createTestSurface()

        for (t in 0 until threadCount) {
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterationsPerThread) {
                        fakeBridge.attachSurface(surface)
                        fakeBridge.detachSurface()
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting surface threads", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Exceptions occurred during concurrent surface ops", 0, errors.get())
        assertEquals(
            "attachSurfaceCount must not drop increments under 16-thread concurrency",
            totalExpected,
            fakeBridge.attachSurfaceCount
        )
        assertEquals(
            "detachSurfaceCount must not drop increments under 16-thread concurrency",
            totalExpected,
            fakeBridge.detachSurfaceCount
        )
        assertEquals(
            "attachedSurfaces collection must record all attached surface instances",
            totalExpected,
            fakeBridge.attachedSurfaces.size
        )
    }

    @Test
    fun testConcurrency_16Threads_concurrentPropertiesAndOptions_noExceptions() {
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
                        fakeBridge.setPropertyDouble("double_$threadId", i * 1.5)
                        fakeBridge.setPropertyLong("long_$threadId", i.toLong())
                        fakeBridge.setPropertyBoolean("flag_$threadId", i % 2 == 0)
                        fakeBridge.setOptionString("opt_$threadId", "opt_val_$i")
                        fakeBridge.observeProperty("obs_$threadId", MpvNativeBridge.Format.STRING)

                        fakeBridge.getPropertyString(propKey)
                        fakeBridge.getPropertyDouble("double_$threadId")
                        fakeBridge.getPropertyLong("long_$threadId")
                        fakeBridge.getPropertyBoolean("flag_$threadId")
                        fakeBridge.isPropertyObserved("obs_$threadId")
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

        assertEquals("Exceptions occurred during concurrent property/option access", 0, errors.get())
        assertEquals(threadCount, fakeBridge.options.size)
        assertEquals(threadCount, fakeBridge.observedProperties.size)
    }

    @Test
    fun testConcurrency_16Threads_heterogeneousStressTest() {
        val threadCount = 16
        val iterations = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)
        val listener = RecordingEventListener()
        fakeBridge.eventListener = listener
        val surface = createTestSurface()

        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterations) {
                        when (threadId % 4) {
                            0 -> {
                                // Commands
                                fakeBridge.command(arrayOf("playlist-next", "t=$threadId", "i=$i"))
                            }
                            1 -> {
                                // Surface & Lifecycle
                                fakeBridge.attachSurface(surface)
                                fakeBridge.detachSurface()
                            }
                            2 -> {
                                // Properties & Options
                                fakeBridge.setPropertyDouble("volume_$threadId", 100.0 + i)
                                fakeBridge.setOptionString("ao_$threadId", "audiotrack")
                                fakeBridge.getPropertyDouble("volume_$threadId")
                            }
                            3 -> {
                                // Events & Simulations
                                fakeBridge.emitPropertyLong("playback-time_$threadId", i.toLong())
                                fakeBridge.emitEvent(MpvNativeBridge.Event.PROPERTY_CHANGE)
                            }
                        }

                        // Parallel read iteration to stress CopyOnWriteArrayList and ConcurrentHashMap iterators
                        var count = 0
                        for (cmd in fakeBridge.commands) {
                            count += cmd.size
                        }
                        for (entry in fakeBridge.properties) {
                            count += entry.key.length
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
        assertTrue("Timeout awaiting heterogeneous stress threads", doneGate.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Zero errors expected under heterogeneous concurrent stress", 0, errors.get())
    }

    @Test
    fun testConcurrency_concurrentResetDuringOperations() {
        val threadCount = 8
        val iterations = 100
        val executor = Executors.newFixedThreadPool(threadCount + 1)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount + 1)
        val errors = AtomicInteger(0)

        // 8 worker threads continuously writing
        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    startGate.await(5, TimeUnit.SECONDS)
                    for (i in 0 until iterations) {
                        fakeBridge.command(arrayOf("test", "$threadId", "$i"))
                        fakeBridge.setPropertyString("key_$threadId", "val_$i")
                        fakeBridge.setOptionString("opt_$threadId", "val_$i")
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneGate.countDown()
                }
            }
        }

        // 1 thread periodically calling reset()
        executor.execute {
            try {
                startGate.await(5, TimeUnit.SECONDS)
                for (i in 0 until 10) {
                    Thread.sleep(2)
                    fakeBridge.reset()
                }
            } catch (e: Throwable) {
                errors.incrementAndGet()
            } finally {
                doneGate.countDown()
            }
        }

        startGate.countDown()
        assertTrue("Timeout awaiting reset concurrency", doneGate.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No exceptions or deadlocks when reset() is called concurrently", 0, errors.get())
    }
}
