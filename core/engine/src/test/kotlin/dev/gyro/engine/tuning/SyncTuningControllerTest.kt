package dev.gyro.engine.tuning

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTuningControllerTest {

    private val bridge = FakeMpvNativeBridge()
    private val controller = SyncTuningController(bridge)
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
    }

    private fun subscribe(): Job {
        val eventScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope = eventScope
        return controller.observeEvents(eventScope)
    }

    @Test
    fun setSubtitleDelay_roundsToTenthsAndWritesProperty() {
        controller.setSubtitleDelay(1.25)
        assertEquals(1.3, controller.subtitleDelaySec.value, 0.0)
        assertEquals(1.3, bridge.properties["sub-delay"] as Double, 0.0)
    }

    @Test
    fun setSubtitleDelay_clampsToMpvRange() {
        controller.setSubtitleDelay(42.0)
        assertEquals(10.0, controller.subtitleDelaySec.value, 0.0)
        assertEquals(10.0, bridge.properties["sub-delay"] as Double, 0.0)

        controller.setSubtitleDelay(-42.0)
        assertEquals(-10.0, controller.subtitleDelaySec.value, 0.0)
        assertEquals(-10.0, bridge.properties["sub-delay"] as Double, 0.0)
    }

    @Test
    fun setAudioDelay_clampsToFiveSeconds() {
        controller.setAudioDelay(-8.0)
        assertEquals(-5.0, controller.audioDelaySec.value, 0.0)
        assertEquals(-5.0, bridge.properties["audio-delay"] as Double, 0.0)
    }

    @Test
    fun setSubtitleScale_clampsBetweenHalfAndTriple() {
        controller.setSubtitleScale(9.0)
        assertEquals(3.0, controller.subtitleScale.value, 0.0)
        assertEquals(3.0, bridge.properties["sub-scale"] as Double, 0.0)

        controller.setSubtitleScale(0.1)
        assertEquals(0.5, controller.subtitleScale.value, 0.0)
    }

    @Test
    fun setSubtitlePosition_clampsToPercentRange() {
        controller.setSubtitlePosition(180L)
        assertEquals(100L, controller.subtitlePositionPercent.value)
        assertEquals(100L, bridge.properties["sub-pos"] as Long)

        controller.setSubtitlePosition(-20L)
        assertEquals(0L, controller.subtitlePositionPercent.value)
    }

    @Test
    fun observedProperties_fromMpvConfReachStateWithoutASetter() {
        subscribe()

        bridge.emitPropertyDouble("sub-delay", -2.5)
        bridge.emitPropertyDouble("audio-delay", 0.4)
        bridge.emitPropertyDouble("sub-scale", 1.4)
        bridge.emitPropertyLong("sub-pos", 88L)

        assertEquals(-2.5, controller.subtitleDelaySec.value, 0.0)
        assertEquals(0.4, controller.audioDelaySec.value, 0.0)
        assertEquals(1.4, controller.subtitleScale.value, 0.0)
        assertEquals(88L, controller.subtitlePositionPercent.value)
    }

    @Test
    fun resetDelays_zerosSubtitleAndAudioDelayWhilePreservingScaleAndPosition() {
        controller.setSubtitleDelay(2.4)
        controller.setAudioDelay(-1.2)
        controller.setSubtitleScale(1.5)
        controller.setSubtitlePosition(85L)

        controller.resetDelays()

        assertEquals(0.0, controller.subtitleDelaySec.value, 0.0)
        assertEquals(0.0, controller.audioDelaySec.value, 0.0)
        assertEquals(0.0, bridge.properties["sub-delay"] as Double, 0.0)
        assertEquals(0.0, bridge.properties["audio-delay"] as Double, 0.0)
        assertEquals(1.5, controller.subtitleScale.value, 0.0)
        assertEquals(85L, controller.subtitlePositionPercent.value)
    }
}
