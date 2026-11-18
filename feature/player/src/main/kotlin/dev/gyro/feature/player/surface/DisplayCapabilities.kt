package dev.gyro.feature.player.surface

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager

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

/** True when the current display advertises HDR support to the platform. */
@Suppress("DEPRECATION")
internal fun Context.displaySupportsHdr(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val display = findActivity()?.windowManager?.defaultDisplay ?: return false
    return display.isHdr
}

/** True when the current display advertises wide color gamut (P3) support. */
@Suppress("DEPRECATION")
internal fun Context.displaySupportsWideColorGamut(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val display = findActivity()?.windowManager?.defaultDisplay ?: return false
    return display.isWideColorGamut
}
