package dev.gyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConfigModelsTest {

    @Test
    fun hwdecFromRuntimeValueParsesAllVariantsAccurately() {
        assertNull(HwdecMode.fromRuntimeValue(""))
        assertNull(HwdecMode.fromRuntimeValue("   "))

        assertEquals(HwdecMode.HARDWARE_DIRECT, HwdecMode.fromRuntimeValue("mediacodec"))
        assertEquals(HwdecMode.HARDWARE_DIRECT, HwdecMode.fromRuntimeValue("mediacodec,mediacodec-copy"))
        assertEquals(HwdecMode.HARDWARE_COPY, HwdecMode.fromRuntimeValue("mediacodec-copy"))
        assertEquals(HwdecMode.SOFTWARE, HwdecMode.fromRuntimeValue("no"))
        assertEquals(HwdecMode.SOFTWARE, HwdecMode.fromRuntimeValue("none"))
        assertEquals(HwdecMode.SOFTWARE, HwdecMode.fromRuntimeValue("sw"))

        assertTrue(HwdecMode.HARDWARE_DIRECT.isHardware)
        assertTrue(HwdecMode.HARDWARE_COPY.isHardware)
        assertFalse(HwdecMode.SOFTWARE.isHardware)
    }

    @Test
    fun hwdecFromPreferenceDefaultsToDirectOnBlank() {
        assertEquals(HwdecMode.HARDWARE_DIRECT, HwdecMode.fromPreference(""))
        assertEquals(HwdecMode.HARDWARE_COPY, HwdecMode.fromPreference(HWDEC_HARDWARE_COPY))
        assertEquals(HwdecMode.SOFTWARE, HwdecMode.fromPreference(HWDEC_SOFTWARE))
    }

    @Test
    fun hdrToneMappingAndScaleParsersMapKeysAndFallbacks() {
        assertEquals(HdrOutputMode.AUTO, HdrOutputMode.fromKey("auto"))
        assertEquals(HdrOutputMode.PQ, HdrOutputMode.fromKey(HDR_OUTPUT_PQ))
        assertEquals(HdrOutputMode.HLG, HdrOutputMode.fromKey(HDR_OUTPUT_HLG))
        assertEquals(HdrOutputMode.SDR, HdrOutputMode.fromKey(HDR_OUTPUT_SDR))
        assertEquals(HdrOutputMode.AUTO, HdrOutputMode.fromKey("unknown"))

        assertEquals(ToneMappingAlgorithm.BT_2446A, ToneMappingAlgorithm.fromKey(TONE_MAPPING_BT2446A))
        assertEquals(ToneMappingAlgorithm.SPLINE, ToneMappingAlgorithm.fromKey(TONE_MAPPING_SPLINE))
        assertEquals(ToneMappingAlgorithm.REINHARD, ToneMappingAlgorithm.fromKey(TONE_MAPPING_REINHARD))
        assertEquals(ToneMappingAlgorithm.HABLE, ToneMappingAlgorithm.fromKey(TONE_MAPPING_HABLE))
        assertEquals(ToneMappingAlgorithm.AUTO, ToneMappingAlgorithm.fromKey(""))

        assertEquals(VideoScale.BILINEAR, VideoScale.fromKey(SCALE_BILINEAR))
        assertEquals(VideoScale.BILINEAR, VideoScale.fromKey("off"))
        assertEquals(VideoScale.LANCZOS, VideoScale.fromKey(SCALE_LANCZOS))
        assertEquals(VideoScale.SPLINE36, VideoScale.fromKey(SCALE_SPLINE36))
        assertEquals(VideoScale.BILINEAR, VideoScale.fromKey("unknown"))
    }

    @Test
    fun userSettingsExposesTypedAccessorsOverPersistedStrings() {
        val settings = UserSettings(
            defaultHwdec = HWDEC_HARDWARE_COPY,
            hdrOutputMode = HDR_OUTPUT_SDR,
            toneMappingAlgorithm = TONE_MAPPING_BT2446A,
            videoScale = SCALE_LANCZOS,
        )

        assertEquals(HwdecMode.HARDWARE_COPY, settings.defaultHwdecMode)
        assertEquals(HdrOutputMode.SDR, settings.hdrOutput)
        assertEquals(ToneMappingAlgorithm.BT_2446A, settings.toneMapping)
        assertEquals(VideoScale.LANCZOS, settings.videoScalePreset)
    }
}
