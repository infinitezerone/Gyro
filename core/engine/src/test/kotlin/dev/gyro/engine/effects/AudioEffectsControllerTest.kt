package dev.gyro.engine.effects

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioEffectsControllerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var controller: AudioEffectsController

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        controller = RealAudioEffectsController(bridge)
    }

    @Test
    fun testInitialState() {
        assertEquals(100.0, controller.softwareVolume.value, 0.001)
        assertFalse(controller.nightMode.value)
        assertFalse(controller.stereoDownmix.value)
        assertEquals(null, bridge.properties["af"])
    }

    @Test
    fun testVolumeOverdrive_setsGainAndActivatesLimiter() {
        controller.setSoftwareVolume(150.0)

        assertEquals(150.0, controller.softwareVolume.value, 0.001)
        assertEquals(150.0, bridge.properties["volume"] as Double, 0.001)
        assertEquals(AudioEffectsController.FILTER_ALIMITER, bridge.properties["af"])
    }

    @Test
    fun testVolumeOverdrive_clamping() {
        controller.setSoftwareVolume(250.0)
        assertEquals(200.0, controller.softwareVolume.value, 0.001)
        assertEquals(200.0, bridge.properties["volume"] as Double, 0.001)

        controller.setSoftwareVolume(50.0)
        assertEquals(100.0, controller.softwareVolume.value, 0.001)
        assertEquals(100.0, bridge.properties["volume"] as Double, 0.001)
    }

    @Test
    fun testVolumeOverdrive_limiterRemovedAt100() {
        controller.setSoftwareVolume(175.0)
        assertEquals(AudioEffectsController.FILTER_ALIMITER, bridge.properties["af"])

        controller.setSoftwareVolume(100.0)
        assertEquals("", bridge.properties["af"])
    }

    @Test
    fun testVolumeOverdrive_resetSoftwareGainSafety() {
        controller.setSoftwareVolume(180.0)
        assertEquals(180.0, controller.softwareVolume.value, 0.001)

        controller.resetSoftwareGain()

        assertEquals(100.0, controller.softwareVolume.value, 0.001)
        assertEquals(100.0, bridge.properties["volume"] as Double, 0.001)
        assertEquals("", bridge.properties["af"])
    }

    @Test
    fun testCompositeAudioFilters_ordering() {
        // 1. Stereo downmix
        controller.setStereoDownmix(true)
        assertEquals(AudioEffectsController.FILTER_STEREO_DOWNMIX, bridge.properties["af"])

        // 2. Night mode
        controller.setNightMode(true)
        val expectedDownmixAndNight =
            "${AudioEffectsController.FILTER_STEREO_DOWNMIX},${AudioEffectsController.FILTER_DYNAUDNORM}"
        assertEquals(expectedDownmixAndNight, bridge.properties["af"])

        // 3. Limiter
        controller.setSoftwareVolume(160.0)
        val expectedAllThree =
            "${AudioEffectsController.FILTER_STEREO_DOWNMIX},${AudioEffectsController.FILTER_DYNAUDNORM},${AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expectedAllThree, bridge.properties["af"])
    }
}
