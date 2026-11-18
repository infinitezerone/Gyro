package dev.gyro.core.model

import kotlinx.serialization.Serializable

/** Hardware decoding. The engine appends the copy-back variant so an init failure is not fatal. */
const val HWDEC_HARDWARE = "mediacodec"

/** Software decoding, in mpv's own spelling. */
const val HWDEC_SOFTWARE = "no"

/** Automatically trigger physical HDR / 10-bit output on supported displays. */
const val HDR_OUTPUT_AUTO = "auto"

/** Force BT.2100 PQ transfer curve output (HDR10 standard). */
const val HDR_OUTPUT_PQ = "pq"

/** Force BT.2100 HLG transfer curve output (Broadcast HDR). */
const val HDR_OUTPUT_HLG = "hlg"

/** Force tone-mapping to SDR for eye-friendly, battery-saving viewing. */
const val HDR_OUTPUT_SDR = "sdr"

/** Tone-mapping algorithms for HDR to SDR / panel peak adaptation. */
const val TONE_MAPPING_AUTO = "auto"
const val TONE_MAPPING_HABLE = "hable"
const val TONE_MAPPING_BT2446A = "bt.2446a"
const val TONE_MAPPING_SPLINE = "spline"
const val TONE_MAPPING_REINHARD = "reinhard"

/** Video scaling / sampling filter presets. */
const val SCALE_SPLINE36 = "spline36"
const val SCALE_LANCZOS = "lanczos"
const val SCALE_BILINEAR = "bilinear"
const val SCALE_AUTO = "spline36"
const val SCALE_OFF = "bilinear"

@Serializable
data class UserSettings(
    val autoResume: Boolean = true,
    val autoPip: Boolean = true,
    val backgroundAudio: Boolean = false,
    val backgroundPlayMode: String = "pip", // Deprecated: preserved for backward compatibility
    val seekStepSeconds: Int = 10, // 5, 10, 15, 30
    val defaultHwdec: String = HWDEC_HARDWARE, // HWDEC_HARDWARE, "mediacodec-copy", HWDEC_SOFTWARE
    val hdrOutputMode: String = HDR_OUTPUT_AUTO, // HDR_OUTPUT_AUTO, HDR_OUTPUT_PQ, HDR_OUTPUT_HLG, HDR_OUTPUT_SDR
    val toneMappingAlgorithm: String = TONE_MAPPING_AUTO,
    val videoScale: String = SCALE_SPLINE36,
    val interpolation: Boolean = false,
    val audioPassthrough: Boolean = false,
    val subAutoLoad: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val gestureConfig: PlayerGestureConfig = PlayerGestureConfig()
) {
    val defaultHwdecMode: HwdecMode
        get() = HwdecMode.fromPreference(defaultHwdec)

    val hdrOutput: HdrOutputMode
        get() = HdrOutputMode.fromKey(hdrOutputMode)

    val toneMapping: ToneMappingAlgorithm
        get() = ToneMappingAlgorithm.fromKey(toneMappingAlgorithm)

    val videoScalePreset: VideoScale
        get() = VideoScale.fromKey(videoScale)
}
