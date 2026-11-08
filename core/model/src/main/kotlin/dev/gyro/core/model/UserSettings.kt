package dev.gyro.core.model

import kotlinx.serialization.Serializable

/** Hardware decoding. The engine appends the copy-back variant so an init failure is not fatal. */
const val HWDEC_HARDWARE = "mediacodec"

/** Software decoding, in mpv's own spelling. */
const val HWDEC_SOFTWARE = "no"

/** Classic mpv GPU video output (OpenGL ES). */
const val VO_LIBMPV = "libmpv"

/** Next-gen libplacebo video output (gpu-next over OpenGL ES). */
const val VO_GPU_NEXT = "gpu-next"

@Serializable
data class UserSettings(
    val autoResume: Boolean = true,
    val backgroundPlayMode: String = "pip", // "pip", "audio_only", "off"
    val seekStepSeconds: Int = 10, // 5, 10, 15, 30
    val defaultHwdec: String = HWDEC_HARDWARE, // HWDEC_HARDWARE, "mediacodec-copy", HWDEC_SOFTWARE
    val voRenderer: String = VO_LIBMPV, // VO_LIBMPV, VO_GPU_NEXT
    val subAutoLoad: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val gestureConfig: PlayerGestureConfig = PlayerGestureConfig()
)
