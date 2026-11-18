package dev.gyro.engine.model

/**
 * The panel's HDR ability, as reported by the platform.
 *
 * Kept as one table rather than the individual booleans it used to be spread over: the output
 * decision needs every fact at once, and "can this device do HDR" is not the same question as
 * "does it understand HLG". A panel that answers the first but not the second still has to be
 * prevented from being handed an HLG-tagged buffer.
 */
data class HdrDisplayCapabilities(
    /** HDR types the panel advertises. Empty for an SDR panel. */
    val supportedHdrTypes: Set<HdrType> = emptySet(),
    /** Peak luminance the panel claims for HDR content, in nits; `0` when not reported. */
    val peakLuminanceNits: Float = 0f,
) {
    val supportsHdr: Boolean get() = supportedHdrTypes.isNotEmpty()

    /** HDR types as Android's [android.view.Display.HdrCapabilities] names them. */
    enum class HdrType { DOLBY_VISION, HDR10, HDR10_PLUS, HLG }

    companion object {
        /** The conservative default: an SDR panel that reports no peak. */
        val SdrOnly = HdrDisplayCapabilities()
    }
}
