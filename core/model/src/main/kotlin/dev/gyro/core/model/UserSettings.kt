package dev.gyro.core.model

import kotlinx.serialization.Serializable

/** Hardware decoding. The engine appends the copy-back variant so an init failure is not fatal. */
const val HWDEC_HARDWARE = "mediacodec"

/** Software decoding, in mpv's own spelling. */
const val HWDEC_SOFTWARE = "no"

/** Classic mpv GPU video output (vo=gpu). */
const val VO_GPU = "gpu"
const val VO_LIBMPV = "gpu"

/** Next-gen libplacebo video output (vo=gpu-next). */
const val VO_GPU_NEXT = "gpu-next"

/**
 * Whether this build's libmpv can create a gpu-next backend through the render API.
 *
 * False for the shipped libmpv 1.0.0 (mpv v0.41.0): `mpv_render_context_create` only exposes
 * `libmpv_gpu` (`video/out/gpu/libmpv_gpu.c`), and `MPV_RENDER_PARAM_BACKEND` does not exist in
 * `render.h` (the enum ends at `SW_POINTER = 20`). Unknown render params are skipped silently, so
 * asking for gpu-next returns success while the session keeps running vo_gpu — which is why the UI
 * must not offer the switch and why libplacebo-only options must not be applied.
 *
 * Upstream support is still an open draft (mpv-player/mpv#16818), absent from every release up to
 * and including v0.42.0. Flip this to true only after shipping a libmpv built with that patch.
 */
const val GPU_NEXT_RENDER_API_SUPPORTED = false

/** Automatically trigger physical HDR / 10-bit output on supported displays. */
const val HDR_OUTPUT_AUTO = "auto"

/** Force tone-mapping to SDR for eye-friendly, battery-saving viewing. */
const val HDR_OUTPUT_SDR = "sdr"

@Serializable
data class UserSettings(
    val autoResume: Boolean = true,
    val autoPip: Boolean = true,
    val backgroundAudio: Boolean = false,
    val backgroundPlayMode: String = "pip", // Deprecated: preserved for backward compatibility
    val seekStepSeconds: Int = 10, // 5, 10, 15, 30
    val defaultHwdec: String = HWDEC_HARDWARE, // HWDEC_HARDWARE, "mediacodec-copy", HWDEC_SOFTWARE
    val voRenderer: String = VO_LIBMPV, // VO_LIBMPV, VO_GPU_NEXT
    val hdrOutputMode: String = HDR_OUTPUT_AUTO, // HDR_OUTPUT_AUTO, HDR_OUTPUT_SDR
    val subAutoLoad: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val gestureConfig: PlayerGestureConfig = PlayerGestureConfig()
)
