package dev.gyro.engine.config

import dev.gyro.core.model.GPU_NEXT_RENDER_API_SUPPORTED
import dev.gyro.core.model.HDR_OUTPUT_SDR
import dev.gyro.core.model.HWDEC_HARDWARE
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VO_GPU
import dev.gyro.core.model.VO_GPU_NEXT
import dev.gyro.core.model.VO_LIBMPV
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.trace.EngineLog
import java.util.concurrent.atomic.AtomicReference
class MpvOptionConfigurator {

    companion object {
        private const val TAG = "MpvOptionConfigurator"

        /** Keys a user config must not override; each one can break the native pipeline. */
        val PROTECTED_OPTION_KEYS: Set<String> = setOf(
            "vo",
            "idle",
            "wid",
            "keep-open",
            "gpu-context"
        )
    }

    /**
     * Immutable snapshot of current options, settings and whether defaults-only initialization took place.
     */
    data class State(
        val userOptions: Map<String, String> = emptyMap(),
        val settings: UserSettings = UserSettings(),
        val isDefaultsOnly: Boolean = true
    ) {
        val isExplicit: Boolean get() = userOptions.isNotEmpty() || settings != UserSettings()
    }

    private val _state = AtomicReference(State())
    val currentState: State get() = _state.get()

    /**
     * Atomically records initial configuration and translates it into mpv options before `bridge.init()`.
     */
    fun applyInitial(
        bridge: MpvNativeBridge,
        userOptions: Map<String, String> = currentState.userOptions,
        settings: UserSettings = currentState.settings
    ) {
        val updated = _state.updateAndGet { current ->
            val hasExplicit = userOptions.isNotEmpty() || settings != UserSettings()
            State(
                userOptions = if (hasExplicit) userOptions else current.userOptions,
                settings = if (hasExplicit) settings else current.settings,
                isDefaultsOnly = !hasExplicit && current.isDefaultsOnly
            )
        }
        apply(bridge, updated.userOptions, updated.settings)
    }

    /**
     * If the engine was previously initialized with defaults only, upgrades it with explicit options.
     */
    fun upgradeIfDefaultsOnly(
        bridge: MpvNativeBridge,
        userOptions: Map<String, String>,
        settings: UserSettings
    ): Boolean {
        var upgraded = false
        _state.updateAndGet { current ->
            val hasExplicit = userOptions.isNotEmpty() || settings != UserSettings()
            if (hasExplicit && current.isDefaultsOnly) {
                upgraded = true
                State(userOptions = userOptions, settings = settings, isDefaultsOnly = false)
            } else {
                current
            }
        }
        if (upgraded) {
            apply(bridge, userOptions, settings)
        }
        return upgraded
    }

    /**
     * Atomically updates configuration in memory and hot-applies it to the running mpv instance.
     */
    fun applyRuntimeUpdate(
        bridge: MpvNativeBridge,
        settings: UserSettings = currentState.settings,
        userOptions: Map<String, String> = currentState.userOptions
    ) {
        _state.updateAndGet {
            State(userOptions = userOptions, settings = settings, isDefaultsOnly = false)
        }
        applyRuntime(bridge, userOptions, settings)
    }

    /**
     * Updates configuration without applying to bridge (e.g. before bridge initialization).
     */
    fun updateConfig(
        userOptions: Map<String, String> = currentState.userOptions,
        settings: UserSettings = currentState.settings
    ) {
        _state.updateAndGet { current ->
            val hasExplicit = userOptions.isNotEmpty() || settings != UserSettings()
            State(
                userOptions = if (hasExplicit) userOptions else current.userOptions,
                settings = if (hasExplicit) settings else current.settings,
                isDefaultsOnly = !hasExplicit && current.isDefaultsOnly
            )
        }
    }

    fun apply(bridge: MpvNativeBridge, userOptions: Map<String, String>, settings: UserSettings) {
        applyUserOptions(bridge, userOptions)
        applyGuiSettings(bridge, userOptions, settings)
    }

    /**
     * Hot-applies updated GUI [settings] and [userOptions] to an already-initialized mpv instance
     * using runtime properties alongside option table updates for subsequent file loads.
     */
    fun applyRuntime(bridge: MpvNativeBridge, userOptions: Map<String, String>, settings: UserSettings) {
        for ((key, value) in userOptions) {
            val normalizedKey = key.trim().lowercase()
            if (normalizedKey in PROTECTED_OPTION_KEYS) {
                EngineLog.w(TAG, "Ignoring protected mpv.conf key '$key' with value '$value'")
                continue
            }
            val optCode = bridge.setOptionString(key, value)
            if (optCode != 0) {
                EngineLog.w(TAG, "mpv rejected option '$key=$value' (code=$optCode)")
            }
            bridge.setPropertyString(key, value)
        }
        val requestedHwdec = settings.defaultHwdec.trim().ifBlank { HWDEC_HARDWARE }
        val hwdecConfig = if (requestedHwdec == HWDEC_HARDWARE) {
            "$HWDEC_HARDWARE,mediacodec-copy"
        } else {
            requestedHwdec
        }
        bridge.setOptionString("hwdec", hwdecConfig)
        bridge.setPropertyString("hwdec", hwdecConfig)

        val subAuto = if (settings.subAutoLoad) "all" else "no"
        bridge.setOptionString("sub-auto", subAuto)
        bridge.setPropertyString("sub-auto", subAuto)

        if (!userOptions.containsKey("tone-mapping")) {
            bridge.setPropertyString("tone-mapping", "hable")
        }
    }

    private fun applyUserOptions(bridge: MpvNativeBridge, userOptions: Map<String, String>) {
        for ((key, value) in userOptions) {
            val normalizedKey = key.trim().lowercase()
            if (normalizedKey in PROTECTED_OPTION_KEYS) {
                EngineLog.w(TAG, "Ignoring protected mpv.conf key '$key' with value '$value'")
                continue
            }
            val code = bridge.setOptionString(key, value)
            if (code != 0) {
                EngineLog.w(TAG, "mpv rejected option '$key=$value' (code=$code)")
            }
        }
    }

    private fun applyGuiSettings(
        bridge: MpvNativeBridge,
        userOptions: Map<String, String>,
        settings: UserSettings
    ) {
        val userHwdec = settings.defaultHwdec.trim()
        val requestedHwdec = userHwdec.ifBlank { HWDEC_HARDWARE }
        val hwdecConfig = if (requestedHwdec == HWDEC_HARDWARE) {
            "$HWDEC_HARDWARE,mediacodec-copy"
        } else {
            requestedHwdec
        }
        bridge.setOptionString("hwdec", hwdecConfig)
        bridge.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        bridge.setOptionString("hwdec-software-fallback", "3")
        bridge.setOptionString("framedrop", "vo")

        bridge.setOptionString("sub-auto", if (settings.subAutoLoad) "all" else "no")

        // Engine lifecycle invariants: mpv_render_context requires vo=libmpv with OpenGL ES.
        bridge.setOptionString("vo", "libmpv")
        bridge.setOptionString("gpu-api", "opengl")
        bridge.setOptionString("fbo-format", "auto")
        bridge.setOptionString("hdr-output", "no")
        // Not an mpv option: it reaches the native EGL layer, which needs the answer before the
        // first surface exists to pick a 10-bit config for the PQ window surface.
        bridge.setOptionString("hdr-capable", if (settings.hdrOutputMode == HDR_OUTPUT_SDR) "no" else "yes")
        bridge.setOptionString("idle", "yes")
        bridge.setOptionString("keep-open", "yes")
        bridge.setOptionString("ao", "audiotrack,opensles")
        bridge.setOptionString("volume-max", "200.0")

        val scaleFilter = when (settings.videoScale) {
            dev.gyro.core.model.SCALE_BILINEAR, dev.gyro.core.model.SCALE_OFF -> "bilinear"
            dev.gyro.core.model.SCALE_LANCZOS -> "lanczos"
            else -> "spline36" // 对齐系统相册级别的高清晰样条重采样
        }
        if (!userOptions.containsKey("scale")) {
            bridge.setOptionString("scale", scaleFilter)
        }
        if (!userOptions.containsKey("cscale")) {
            bridge.setOptionString("cscale", "spline36")
        }
        if (!userOptions.containsKey("dscale")) {
            bridge.setOptionString("dscale", if (scaleFilter == "bilinear") "bilinear" else "mitchell")
        }
        if (!userOptions.containsKey("correct-downscaling")) {
            bridge.setOptionString("correct-downscaling", "yes")
        }
        if (!userOptions.containsKey("linear-downscaling")) {
            bridge.setOptionString("linear-downscaling", "yes")
        }
        if (!userOptions.containsKey("sigmoid-upscaling")) {
            bridge.setOptionString("sigmoid-upscaling", "yes")
        }
        if (!userOptions.containsKey("dither-depth")) {
            bridge.setOptionString("dither-depth", "auto")
        }

        if (!userOptions.containsKey("video-sync")) {
            bridge.setOptionString("video-sync", "audio")
        }
        if (!userOptions.containsKey("demuxer-max-bytes")) {
            bridge.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        }
        if (!userOptions.containsKey("demuxer-max-back-bytes")) {
            bridge.setOptionString("demuxer-max-back-bytes", "${64 * 1024 * 1024}")
        }
        if (!userOptions.containsKey("demuxer-readahead-secs")) {
            bridge.setOptionString("demuxer-readahead-secs", "64")
        }
        if (!userOptions.containsKey("demuxer-seekable-cache")) {
            bridge.setOptionString("demuxer-seekable-cache", "yes")
        }
        if (!userOptions.containsKey("tone-mapping")) {
            bridge.setOptionString("tone-mapping", "hable")
        }
        if (!userOptions.containsKey("hdr-compute-peak")) {
            bridge.setOptionString("hdr-compute-peak", "yes")
        }
        if (!userOptions.containsKey("tone-mapping-mode")) {
            bridge.setOptionString("tone-mapping-mode", "auto")
        }
        if (!userOptions.containsKey("gamut-clipping")) {
            bridge.setOptionString("gamut-clipping", "no")
        }
        if (!userOptions.containsKey("sub-font-provider")) {
            bridge.setOptionString("sub-font-provider", "none")
        }
        if (!userOptions.containsKey("sub-ass-override")) {
            bridge.setOptionString("sub-ass-override", "no")
        }
        if (!userOptions.containsKey("blend-subtitles")) {
            bridge.setOptionString("blend-subtitles", "no")
        }
    }
}
