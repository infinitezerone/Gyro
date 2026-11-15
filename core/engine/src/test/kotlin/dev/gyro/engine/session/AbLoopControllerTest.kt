package dev.gyro.engine.session

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbLoopControllerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var store: PlaybackStore
    private lateinit var controller: AbLoopController

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        store = PlaybackStore()
        controller = AbLoopController(bridge, store)
        controller.registerObservers()
    }

    @Test
    fun registerObservers_registersAbLoopAAndBAsStrings() {
        assertTrue(bridge.observedProperties.containsKey("ab-loop-a"))
        assertEquals(MpvNativeBridge.Format.STRING, bridge.observedProperties["ab-loop-a"])
        assertTrue(bridge.observedProperties.containsKey("ab-loop-b"))
        assertEquals(MpvNativeBridge.Format.STRING, bridge.observedProperties["ab-loop-b"])
    }

    @Test
    fun initialState_hasNoLoop() {
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)
        assertEquals(-1.0, controller.abLoopBSeconds.value, 0.0001)
        assertEquals(-1.0, store.state.value.abLoopASeconds, 0.0001)
        assertEquals(-1.0, store.state.value.abLoopBSeconds, 0.0001)
    }

    @Test
    fun cycleLoopPoint_threeStageLifecycle() {
        // Stage 1: Neither A nor B is set -> issue "ab-loop"
        controller.cycleLoopPoint()
        assertEquals(listOf("ab-loop"), bridge.commands.last())

        // Simulate MPV setting point A at 10.5 seconds
        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "10.500000"))
        assertEquals(10.5, controller.abLoopASeconds.value, 0.0001)
        assertEquals(10.5, store.state.value.abLoopASeconds, 0.0001)
        assertEquals(-1.0, controller.abLoopBSeconds.value, 0.0001)

        // Stage 2: A is set, B is not -> issue "ab-loop" to set B
        controller.cycleLoopPoint()
        assertEquals(listOf("ab-loop"), bridge.commands.last())

        // Simulate MPV setting point B at 25.0 seconds
        controller.onEvent(MpvEvent.StringProperty("ab-loop-b", "25.000000"))
        assertEquals(10.5, controller.abLoopASeconds.value, 0.0001)
        assertEquals(25.0, controller.abLoopBSeconds.value, 0.0001)
        assertEquals(25.0, store.state.value.abLoopBSeconds, 0.0001)

        // Stage 3: Both A and B are set -> cycle clears loop
        controller.cycleLoopPoint()
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)
        assertEquals(-1.0, controller.abLoopBSeconds.value, 0.0001)
        assertEquals("no", bridge.properties["ab-loop-a"])
        assertEquals("no", bridge.properties["ab-loop-b"])
    }

    @Test
    fun explicitSetLoopAAndB_updatesMpvPropertiesAndState() {
        controller.setLoopA(12.34)
        assertEquals(12.34, controller.abLoopASeconds.value, 0.0001)
        assertEquals("12.34", bridge.properties["ab-loop-a"])

        controller.setLoopB(56.78)
        assertEquals(56.78, controller.abLoopBSeconds.value, 0.0001)
        assertEquals("56.78", bridge.properties["ab-loop-b"])

        controller.clearLoop()
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)
        assertEquals(-1.0, controller.abLoopBSeconds.value, 0.0001)
        assertEquals("no", bridge.properties["ab-loop-a"])
        assertEquals("no", bridge.properties["ab-loop-b"])
    }

    @Test
    fun lifecycleEvents_resetLoopPoints() {
        controller.setLoopA(10.0)
        controller.setLoopB(20.0)

        controller.onEvent(MpvEvent.Lifecycle(MpvNativeBridge.Event.FILE_LOADED))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)
        assertEquals(-1.0, controller.abLoopBSeconds.value, 0.0001)

        controller.setLoopA(15.0)
        controller.onEvent(MpvEvent.Lifecycle(MpvNativeBridge.Event.END_FILE))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)

        controller.setLoopA(20.0)
        controller.onEvent(MpvEvent.Lifecycle(MpvNativeBridge.Event.SHUTDOWN))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)
    }

    @Test
    fun propertyParsing_handlesEdgeCases() {
        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "no"))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)

        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "NO"))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)

        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", ""))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)

        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "invalid_number"))
        assertEquals(-1.0, controller.abLoopASeconds.value, 0.0001)

        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "0.0"))
        assertEquals(0.0, controller.abLoopASeconds.value, 0.0001)

        controller.onEvent(MpvEvent.StringProperty("ab-loop-a", "123.456"))
        assertEquals(123.456, controller.abLoopASeconds.value, 0.0001)
    }
}
