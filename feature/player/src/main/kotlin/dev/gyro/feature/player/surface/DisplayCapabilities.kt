package dev.gyro.feature.player.surface

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.WindowManager
import dev.gyro.engine.model.HdrDisplayCapabilities
import dev.gyro.engine.model.HdrDisplayCapabilities.HdrType

/** Resolves the physical (uncropped, pre-displayCut) pixel bounds of the default display. */
internal fun resolvePhysicalDisplaySize(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return Pair(bounds.width(), bounds.height())
        }
    }
    val metrics = context.applicationContext.resources.displayMetrics
    return Pair(metrics.widthPixels, metrics.heightPixels)
}

internal fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * The whole-panel HDR capability table, including the peak luminance `target-peak` needs.
 *
 * `Display.isHdr` only answers "some HDR", which cannot distinguish a panel that understands HLG
 * from one that does not — and handing an HLG-tagged buffer to the latter is exactly the failure
 * this table prevents. `HdrCapabilities.supportedHdrTypes` is the per-type answer.
 */
@Suppress("DEPRECATION")
internal fun Context.displayHdrCapabilities(): HdrDisplayCapabilities {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return HdrDisplayCapabilities.SdrOnly
    val display: Display = findActivity()?.windowManager?.defaultDisplay
        ?: return HdrDisplayCapabilities.SdrOnly
    val capabilities = display.hdrCapabilities ?: return HdrDisplayCapabilities.SdrOnly

    val types = mutableSetOf<HdrType>()
    for (type in capabilities.supportedHdrTypes ?: IntArray(0)) {
        when (type) {
            android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> types.add(HdrType.DOLBY_VISION)
            android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> types.add(HdrType.HDR10)
            android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> types.add(HdrType.HLG)
            // HDR10+ is HDR10 with dynamic metadata, so the HDR10 path already covers it.
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                type == android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
            ) {
                types.add(HdrType.HDR10_PLUS)
            }
        }
    }

    val peak = capabilities.desiredMaxLuminance
    return HdrDisplayCapabilities(
        supportedHdrTypes = types,
        peakLuminanceNits = if (peak.isFinite() && peak > 0f) peak else 0f,
    )
}

/** True when the current display advertises wide color gamut (P3) support. */
@Suppress("DEPRECATION")
internal fun Context.displaySupportsWideColorGamut(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val display = findActivity()?.windowManager?.defaultDisplay ?: return false
    return display.isWideColorGamut
}
