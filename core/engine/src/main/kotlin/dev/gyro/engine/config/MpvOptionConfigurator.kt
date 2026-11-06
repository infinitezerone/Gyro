package dev.gyro.engine.config

import android.util.Log
import dev.gyro.core.model.HWDEC_HARDWARE
import dev.gyro.core.model.UserSettings
import dev.gyro.engine.MpvNativeBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates `mpv.conf` and GUI settings into mpv options.
 *
 * mpv only accepts options before the core exists, so [apply] runs synchronously between
 * `create()` and `init()`. Three layers, in order:
 *
 * 1. user `mpv.conf` entries, minus the keys that can break the native pipeline;
 * 2. GUI settings, which always win over the file;
 * 3. defaults for the keys the user did not provide at all.
 *
 * Nothing here reads or writes playback state: it is a one-shot translation of configuration into
 * options, which is why it can be tested without an engine.
 */
@Singleton
class MpvOptionConfigurator @Inject constructor() {

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

    fun apply(bridge: MpvNativeBridge, userOptions: Map<String, String>, settings: UserSettings) {
        applyUserOptions(bridge, userOptions)
        applyGuiSettings(bridge, userOptions, settings)
    }

    private fun applyUserOptions(bridge: MpvNativeBridge, userOptions: Map<String, String>) {
        for ((key, value) in userOptions) {
            val normalizedKey = key.trim().lowercase()
            if (normalizedKey in PROTECTED_OPTION_KEYS) {
                Log.w(TAG, "Ignoring protected mpv.conf key '$key' with value '$value'")
                continue
            }
            val code = bridge.setOptionString(key, value)
            if (code != 0) {
                Log.w(TAG, "mpv rejected option '$key=$value' (code=$code)")
            }
        }
    }

    private fun applyGuiSettings(
        bridge: MpvNativeBridge,
        userOptions: Map<String, String>,
        settings: UserSettings
    ) {
        val userHwdec = settings.defaultHwdec.trim()
        // Hardware is the default: software decoding caps a phone at 1080p H.264 and cannot play 4K
        // HEVC at all. Failures go to mpv's own two fallbacks rather than to the default value:
        // `mediacodec,mediacodec-copy` lets a codec that refuses the surface play through copy-back,
        // and `hwdec-software-fallback` (set below) continues on libavcodec after N frames fail.
        val requestedHwdec = userHwdec.ifBlank { HWDEC_HARDWARE }
        val hwdecConfig = if (requestedHwdec == HWDEC_HARDWARE) {
            "$HWDEC_HARDWARE,mediacodec-copy"
        } else {
            requestedHwdec
        }
        bridge.setOptionString("hwdec", hwdecConfig)
        bridge.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // Set after mpv.conf so a user config cannot silently turn the fallback off.
        bridge.setOptionString("hwdec-software-fallback", "3")
        // `vo` only, which is mpv's recommended mode and what mpv-android runs: the video output
        // drops the frame at the last moment, when it is known the display cannot show it. Adding
        // `decoder` lets the decoder guess instead, and mpv documents that as "not recommended ...
        // can produce unwatchable choppy output".
        bridge.setOptionString("framedrop", "vo")

        bridge.setOptionString("sub-auto", if (settings.subAutoLoad) "all" else "no")

        // Engine lifecycle invariants: not user configurable, whatever mpv.conf says.
        bridge.setOptionString("vo", "libmpv")
        bridge.setOptionString("idle", "yes")
        bridge.setOptionString("keep-open", "yes")
        bridge.setOptionString("ao", "audiotrack,opensles")
        bridge.setOptionString("volume-max", "200.0")

        if (!userOptions.containsKey("video-sync")) {
            bridge.setOptionString("video-sync", "audio")
        }
        if (!userOptions.containsKey("demuxer-max-bytes")) {
            bridge.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        }
        if (!userOptions.containsKey("demuxer-max-back-bytes")) {
            bridge.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
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
