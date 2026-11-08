package dev.gyro.engine.config

import dev.gyro.core.model.HWDEC_HARDWARE
import dev.gyro.core.model.UserSettings
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
        val voBackend = if (settings.voRenderer == dev.gyro.core.model.VO_GPU_NEXT) {
            dev.gyro.core.model.VO_GPU_NEXT
        } else {
            dev.gyro.core.model.VO_LIBMPV
        }
        val isGpuNext = voBackend == dev.gyro.core.model.VO_GPU_NEXT

        val userHwdec = settings.defaultHwdec.trim()
        val requestedHwdec = userHwdec.ifBlank { HWDEC_HARDWARE }
        val hwdecConfig = if (requestedHwdec == HWDEC_HARDWARE) {
            if (isGpuNext) "mediacodec-copy,mediacodec" else "$HWDEC_HARDWARE,mediacodec-copy"
        } else {
            requestedHwdec
        }
        bridge.setOptionString("hwdec", hwdecConfig)
        bridge.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        bridge.setOptionString("hwdec-software-fallback", "3")
        bridge.setOptionString("framedrop", "vo")

        bridge.setOptionString("sub-auto", if (settings.subAutoLoad) "all" else "no")

        // Engine lifecycle invariants: legacy/EGL requires vo=libmpv, while modern Vulkan uses vo=gpu-next.
        bridge.setOptionString("vo-backend", voBackend)
        if (isGpuNext) {
            bridge.setOptionString("vo", dev.gyro.core.model.VO_GPU_NEXT)
            bridge.setOptionString("gpu-api", "vulkan")
            bridge.setOptionString("gpu-context", "androidvk")
        } else {
            bridge.setOptionString("vo", dev.gyro.core.model.VO_LIBMPV)
            bridge.setOptionString("gpu-api", "opengl")
        }
        bridge.setOptionString("idle", "yes")
        bridge.setOptionString("keep-open", "yes")
        bridge.setOptionString("ao", "audiotrack,opensles")
        bridge.setOptionString("volume-max", "200.0")

        if (!userOptions.containsKey("video-sync")) {
            bridge.setOptionString("video-sync", "audio")
        }
        if (!userOptions.containsKey("demuxer-max-bytes")) {
            bridge.setOptionString("demuxer-max-bytes", "${128 * 1024 * 1024}")
        }
        if (!userOptions.containsKey("demuxer-max-back-bytes")) {
            bridge.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        }
        if (!userOptions.containsKey("demuxer-readahead-secs")) {
            bridge.setOptionString("demuxer-readahead-secs", "20")
        }
        if (!userOptions.containsKey("tone-mapping")) {
            val defaultToneMapping = if (voBackend == dev.gyro.core.model.VO_GPU_NEXT) "reinhard" else "hable"
            bridge.setOptionString("tone-mapping", defaultToneMapping)
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
