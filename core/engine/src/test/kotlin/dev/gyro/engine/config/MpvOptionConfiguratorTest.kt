package dev.gyro.engine.config

import dev.gyro.core.model.UserSettings
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MpvOptionConfiguratorTest {

    private val bridge = FakeMpvNativeBridge()
    private val configurator = MpvOptionConfigurator()

    @Test
    fun protectedKeysFromMpvConfAreDroppedAndInvariantsApplied() {
        configurator.apply(
            bridge,
            mapOf("vo" to "gpu", "wid" to "123", "idle" to "no", "keep-open" to "no"),
            UserSettings()
        )

        assertEquals("libmpv", bridge.options["vo"])
        assertEquals("yes", bridge.options["idle"])
        assertEquals("yes", bridge.options["keep-open"])
        assertFalse(bridge.options.containsKey("wid"))
    }

    @Test
    fun guiSettingsWinOverMpvConf() {
        configurator.apply(
            bridge,
            mapOf("hwdec" to "no", "sub-auto" to "fuzzy"),
            UserSettings(defaultHwdec = "mediacodec-copy", subAutoLoad = true)
        )

        assertEquals("mediacodec-copy", bridge.options["hwdec"])
        assertEquals("all", bridge.options["sub-auto"])
    }

    @Test
    fun hardwareDecodingIsTheDefault() {
        configurator.apply(bridge, emptyMap(), UserSettings(defaultHwdec = ""))

        assertEquals("mediacodec,mediacodec-copy", bridge.options["hwdec"])
        assertEquals("3", bridge.options["hwdec-software-fallback"])
        assertEquals("vo", bridge.options["framedrop"])
    }

    @Test
    fun userMpvConfKeepsTheKeysTheEngineDoesNotOwn() {
        configurator.apply(
            bridge,
            mapOf("video-sync" to "display-resample", "demuxer-max-bytes" to "8MiB"),
            UserSettings()
        )

        assertEquals("display-resample", bridge.options["video-sync"])
        assertEquals("8MiB", bridge.options["demuxer-max-bytes"])
        // Defaults still fill every key the user did not provide.
        assertEquals("${32 * 1024 * 1024}", bridge.options["demuxer-max-back-bytes"])
    }

    @Test
    fun defaultsFillTheGaps() {
        configurator.apply(bridge, emptyMap(), UserSettings())

        assertEquals("audio", bridge.options["video-sync"])
        assertEquals("none", bridge.options["sub-font-provider"])
        assertEquals("no", bridge.options["sub-ass-override"])
        assertEquals("no", bridge.options["blend-subtitles"])
        assertEquals("audiotrack,opensles", bridge.options["ao"])
        assertEquals("200.0", bridge.options["volume-max"])
    }

    @Test
    fun statefulConfigLifecycle_defaultsThenExplicitUpgrade() {
        val configurator = MpvOptionConfigurator()
        val bridge = FakeMpvNativeBridge()

        configurator.applyInitial(bridge, emptyMap(), UserSettings())
        org.junit.Assert.assertTrue(configurator.currentState.isDefaultsOnly)

        val upgraded = configurator.upgradeIfDefaultsOnly(
            bridge,
            mapOf("speed" to "1.5"),
            UserSettings(defaultHwdec = "mediacodec-copy")
        )
        org.junit.Assert.assertTrue(upgraded)
        assertFalse(configurator.currentState.isDefaultsOnly)
        assertEquals("mediacodec-copy", bridge.options["hwdec"])

        // Second upgrade attempt should be a no-op since it's already configured
        val secondUpgrade = configurator.upgradeIfDefaultsOnly(
            bridge,
            mapOf("speed" to "2.0"),
            UserSettings()
        )
        assertFalse(secondUpgrade)
    }

    @Test
    fun statefulConfigLifecycle_runtimeHotUpdate() {
        val configurator = MpvOptionConfigurator()
        val bridge = FakeMpvNativeBridge()

        configurator.applyRuntimeUpdate(
            bridge,
            UserSettings(defaultHwdec = "no", subAutoLoad = false),
            mapOf("panscan" to "1.0")
        )
        assertEquals("no", bridge.properties["hwdec"])
        assertEquals("no", bridge.properties["sub-auto"])
        assertEquals("1.0", bridge.properties["panscan"])
        assertFalse(configurator.currentState.isDefaultsOnly)
    }

    @Test
    fun gpuNextEnablesVulkanAndCopyBackHardwareDecoding() {
        val configurator = MpvOptionConfigurator()
        val bridge = FakeMpvNativeBridge()

        configurator.apply(
            bridge,
            emptyMap(),
            UserSettings(voRenderer = dev.gyro.core.model.VO_GPU_NEXT)
        )

        assertEquals("vulkan", bridge.options["gpu-api"])
        assertEquals("androidvk", bridge.options["gpu-context"])
        assertEquals("gpu-next", bridge.options["vo-backend"])
        assertEquals("gpu-next", bridge.options["vo"])
        assertEquals("mediacodec-copy,mediacodec", bridge.options["hwdec"])
    }
}
