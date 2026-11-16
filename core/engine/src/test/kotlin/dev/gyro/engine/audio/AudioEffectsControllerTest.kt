package dev.gyro.engine.audio

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import java.util.Locale
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
        controller = AudioEffectsController(bridge)
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

    @Test
    fun testUserBaseAf_isPreservedWhenEffectsChange() {
        controller.userBaseAf = "lavfi=[bs2b=profile=default]"
        controller.setSoftwareVolume(150.0)

        val expected = "lavfi=[bs2b=profile=default],${AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expected, bridge.properties["af"])

        controller.setNightMode(true)
        val expectedWithNight = "lavfi=[bs2b=profile=default],${AudioEffectsController.FILTER_DYNAUDNORM},${AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expectedWithNight, bridge.properties["af"])

        controller.setSoftwareVolume(100.0)
        controller.setNightMode(false)
        assertEquals("lavfi=[bs2b=profile=default]", bridge.properties["af"])
    }

    @Test
    fun testEqualizer_appliesLavfiFilterAndLimiterWhenBoosted() {
        controller.setEqualizerGains(listOf(3.5, 0.0, -2.0, 0.0, 0.0))

        val expected = "lavfi=[equalizer=f=60:width_type=o:width=1.5:g=3.5,equalizer=f=910:width_type=o:width=1.5:g=-2.0],${AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expected, bridge.properties["af"])
    }

    @Test
    fun testEqualizer_cutOnlyDoesNotTriggerLimiter() {
        controller.setEqualizerGains(listOf(-3.0, -1.0, 0.0, 0.0, 0.0))

        val expected = "lavfi=[equalizer=f=60:width_type=o:width=1.5:g=-3.0,equalizer=f=230:width_type=o:width=1.5:g=-1.0]"
        assertEquals(expected, bridge.properties["af"])
    }

    @Test
    fun testEqualizer_localeIndependentDecimalSeparator() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            controller.setEqualizerGains(listOf(3.5, 0.0, 0.0, 0.0, 0.0))

            val expected = "lavfi=[equalizer=f=60:width_type=o:width=1.5:g=3.5],${AudioEffectsController.FILTER_ALIMITER}"
            assertEquals(expected, bridge.properties["af"])
            assertFalse(
                "Filter string must not contain comma as decimal separator",
                (bridge.properties["af"] as String).contains("3,5")
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
