package dev.gyro.core.model

/** Copy-back hardware decoding string key. */
const val HWDEC_HARDWARE_COPY = "mediacodec-copy"

/** Five-band graphical equalizer centre frequencies in Hz. */
val EQUALIZER_BANDS: List<Double> = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)

/** Maximum symmetrical boost/cut in dB per equalizer band. */
const val EQUALIZER_GAIN_LIMIT_DB: Double = 12.0

/**
 * Strongly typed video decoder preference / active mode, decoupling feature and domain
 * layers from backend-specific mpv strings such as `"mediacodec,mediacodec-copy"` or `"no"`.
 */
enum class HwdecMode(
    val persistedKey: String,
    val isHardware: Boolean,
) {
    /** Direct surface zero-copy hardware decode (with copy-back fallback configured by the engine). */
    HARDWARE_DIRECT(persistedKey = HWDEC_HARDWARE, isHardware = true),

    /** Hardware decode with frame copy-back to system memory for GLSL shaders and post-processing. */
    HARDWARE_COPY(persistedKey = HWDEC_HARDWARE_COPY, isHardware = true),

    /** Pure CPU software decoding. */
    SOFTWARE(persistedKey = HWDEC_SOFTWARE, isHardware = false);

    companion object {
        /**
         * Parses a runtime decoder report (for example `"mediacodec"`, `"mediacodec,mediacodec-copy"`,
         * `"mediacodec-copy"`, `"no"`, `"none"`, or blank) into a typed [HwdecMode].
         *
         * Returns `null` when [raw] is blank so callers can distinguish an unreported state from
         * explicit software decoding.
         */
        fun fromRuntimeValue(raw: String): HwdecMode? {
            val normalized = raw.trim().lowercase()
            if (normalized.isEmpty()) return null
            // Examine the primary token before any fallback list (e.g. "mediacodec,mediacodec-copy").
            val primary = normalized.substringBefore(',').trim()
            return when {
                primary == "no" || primary == "none" || primary == "sw" || primary == "software" -> SOFTWARE
                primary.contains("copy") -> HARDWARE_COPY
                else -> HARDWARE_DIRECT
            }
        }

        /** Parses a persisted [UserSettings.defaultHwdec] value, defaulting to [HARDWARE_DIRECT]. */
        fun fromPreference(raw: String): HwdecMode = fromRuntimeValue(raw) ?: HARDWARE_DIRECT
    }
}

/**
 * Strongly typed HDR panel output transfer mode.
 */
enum class HdrOutputMode(val persistedKey: String) {
    AUTO(HDR_OUTPUT_AUTO),
    PQ(HDR_OUTPUT_PQ),
    HLG(HDR_OUTPUT_HLG),
    SDR(HDR_OUTPUT_SDR);

    companion object {
        fun fromKey(raw: String): HdrOutputMode {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.persistedKey == normalized } ?: AUTO
        }
    }
}

/**
 * Strongly typed HDR-to-SDR / panel peak tone-mapping curve preset.
 */
enum class ToneMappingAlgorithm(val persistedKey: String) {
    AUTO(TONE_MAPPING_AUTO),
    HABLE(TONE_MAPPING_HABLE),
    BT_2446A(TONE_MAPPING_BT2446A),
    SPLINE(TONE_MAPPING_SPLINE),
    REINHARD(TONE_MAPPING_REINHARD);

    companion object {
        fun fromKey(raw: String): ToneMappingAlgorithm {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull { it.persistedKey == normalized } ?: AUTO
        }
    }
}

/**
 * Strongly typed video resampling / scaling filter preset.
 */
enum class VideoScale(val persistedKey: String) {
    SPLINE36(SCALE_SPLINE36),
    LANCZOS(SCALE_LANCZOS),
    BILINEAR(SCALE_BILINEAR);

    companion object {
        fun fromKey(raw: String): VideoScale {
            return when (raw.trim().lowercase()) {
                SCALE_BILINEAR, "off" -> BILINEAR
                SCALE_LANCZOS -> LANCZOS
                SCALE_SPLINE36 -> SPLINE36
                else -> BILINEAR
            }
        }
    }
}
