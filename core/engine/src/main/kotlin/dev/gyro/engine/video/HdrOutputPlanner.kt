package dev.gyro.engine.video

import dev.gyro.core.model.HdrOutputMode
import dev.gyro.engine.model.HdrDisplayCapabilities
import dev.gyro.engine.model.HdrDisplayCapabilities.HdrType

/**
 * What the decoder says the source actually is.
 *
 * Derived once from `video-params` so the string comparisons live in one place instead of being
 * repeated in the HDR state machine and in the UI label mapping.
 */
enum class HdrFormat(val label: String) {
    NONE(""),
    HDR10("HDR10"),
    HLG("HLG"),
    DOLBY_VISION("Dolby Vision");

    val isHdr: Boolean get() = this != NONE
}

/**
 * Transfer curve the pipeline is going to write.
 *
 * [mpvTrc] is the `target-trc` value; the window surface colours space is derived from the same
 * field, which is the entire point — the two layers can no longer disagree.
 */
enum class OutputTransfer(val mpvTrc: String) {
    /** Let mpv choose. Used for SDR sources and whenever HDR output is off. */
    AUTO("auto"),
    PQ("pq"),
    HLG("hlg");

    val isHdr: Boolean get() = this != AUTO
}

/** What the EGL implementation can actually do. `null` means the render thread has not probed yet. */
data class HdrColorSpaceSupport(
    val pq: Boolean? = null,
    val hlg: Boolean? = null,
) {
    companion object {
        /** Before the first probe: assume PQ works, so a missed probe does not kill HDR outright. */
        val Unknown = HdrColorSpaceSupport()
    }
}

/**
 * The complete output intent for one file: everything that has to move together.
 *
 * Bundling them is the fix for the original defect class — a pipeline where `target-trc` was
 * written in one place and the surface colours space in another had no way to stay consistent.
 */
data class HdrOutputTarget(
    val transfer: OutputTransfer = OutputTransfer.AUTO,
    /** `target-prim`: `bt.2020` for HDR output, `bt.709` while folding into SDR. */
    val primaries: String = "auto",
    /** Panel peak in nits used for `target-peak`; `0` leaves mpv to resolve `auto`. */
    val peakNits: Float = 0f,
    /** HDR -> SDR folding needs the BT.2020 gamut pulled back into BT.709. */
    val gamutClipping: Boolean = false,
    /** True when HDR content is being folded into SDR, which is what selects the tone curve. */
    val foldsToSdr: Boolean = false,
    /** Why this target won, so a field report about washed-out colours is debuggable a month later. */
    val reason: String = "",
) {
    val isHdr: Boolean get() = transfer.isHdr
}

/**
 * Decides the HDR output target. Pure, so the whole decision table is unit-testable without an
 * engine, a display, or mpv.
 *
 * The ladder only ever degrades: a preference can lower the target (user picks SDR, panel is SDR,
 * EGL cannot do PQ, source is Dolby Vision) but nothing here may promote past what the hardware
 * proved it can do.
 */
object HdrOutputPlanner {

    fun plan(
        format: HdrFormat,
        display: HdrDisplayCapabilities,
        colorSpace: HdrColorSpaceSupport,
        preference: HdrOutputMode,
    ): HdrOutputTarget {
        val peak = display.peakLuminanceNits

        if (!format.isHdr) {
            // SDR source: hand the pipeline back to mpv untouched, whatever the preference says.
            return HdrOutputTarget(reason = "sdr-source")
        }

        fun fold(reason: String) = HdrOutputTarget(
            transfer = OutputTransfer.AUTO,
            primaries = "bt.709",
            // Deliberately NOT the panel peak. `target-peak` is the brightness the tone mapper aims
            // at, so handing it a 2000-nit HDR peak while encoding an SDR curve maps the whole
            // picture onto a 2000-nit range and reads as blown out. `auto` lets mpv use its own SDR
            // reference, which is what this path did before the planner existed.
            peakNits = 0f,
            // mpv's hue-preserving gamut mapping beats hard clipping for HDR -> SDR; the project
            // config has always run with clipping off.
            gamutClipping = false,
            foldsToSdr = true,
            reason = reason,
        )

        // Dolby Vision is never passed through: profile 5 is ICtCp, which vo=gpu cannot convert,
        // and the resulting tint is worse than an honest SDR fold.
        if (format == HdrFormat.DOLBY_VISION) return fold("dolby-vision-unsupported")

        if (!display.supportsHdr) return fold("panel-sdr")
        if (preference == HdrOutputMode.SDR) return fold("user-forced-sdr")

        val pqReady = colorSpace.pq ?: true
        // An HLG surface tag is only worth asking for when both the driver and the panel know HLG.
        val hlgReady = colorSpace.hlg == true && HdrType.HLG in display.supportedHdrTypes
        val isHlgSource = format == HdrFormat.HLG
        // HLG is display-referred by design: the panel is *supposed* to apply its own peak. Converting
        // it to PQ throws that away and demands mastering metadata we cannot supply, which is how a
        // PQ pass-through ends up dimmer and flatter than the same file in the system gallery. Only an
        // explicit PQ preference should force the conversion.
        val wantsHlg = isHlgSource && hlgReady && preference != HdrOutputMode.PQ

        if (wantsHlg) {
            return HdrOutputTarget(
                transfer = OutputTransfer.HLG,
                primaries = "bt.2020",
                peakNits = peak,
                reason = "hlg-pass-through",
            )
        }
        if (pqReady) {
            return HdrOutputTarget(
                transfer = OutputTransfer.PQ,
                primaries = "bt.2020",
                peakNits = peak,
                reason = when {
                    isHlgSource && preference == HdrOutputMode.PQ -> "hlg-forced-to-pq"
                    isHlgSource -> "hlg-to-pq"
                    else -> "pq-pass-through"
                },
            )
        }
        return fold(
            when {
                isHlgSource && colorSpace.hlg != true -> "hlg-colorspace-unavailable"
                else -> "no-pq-colorspace"
            },
        )
    }
}
