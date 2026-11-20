package dev.gyro.feature.player

import dev.gyro.feature.player.component.sheet.PlayerSheetRoute

/**
 * Returns true when the video canvas should shrink/shift to fit above the portrait bottom sheet.
 * Covers all bottom sheets (settings, playlist, and chapters) unified onto `AnchoredSheetScaffold`.
 */
internal fun isSheetFitActive(
    activeSheetTab: PlayerSheetRoute?,
    isLandscape: Boolean,
    isPipLayout: Boolean,
): Boolean = activeSheetTab != null &&
    !isLandscape &&
    !isPipLayout

/**
 * How the persistent video surface is fitted into the space left above the portrait bottom sheet.
 *
 * [scale] is 1 for a picture that already fits in the remaining height (a 16:9 clip on a tall
 * phone) and shrinks it only when it would otherwise be covered - the vertical-video case, where
 * the picture is taller than the gap. [shiftY] moves the picture up so its centre lands in the
 * middle of the remaining space; that is half the sheet height regardless of scale, because the
 * picture is centred in the full container to begin with.
 */
internal data class SheetFitTransform(
    val scale: Float,
    val shiftY: Float,
)

/** Lower bound so a degenerate sheet height cannot collapse the picture to nothing. */
private const val MIN_SHEET_FIT_SCALE = 0.2f

internal fun sheetFitTransform(
    videoHeightPx: Float,
    screenHeightPx: Float,
    sheetHeightPx: Float,
    active: Boolean,
): SheetFitTransform {
    if (!active || screenHeightPx <= 0f || sheetHeightPx <= 0f) {
        return SheetFitTransform(scale = 1f, shiftY = 0f)
    }
    val available = (screenHeightPx - sheetHeightPx).coerceAtLeast(0f)
    val scale = if (videoHeightPx > available && videoHeightPx > 0f) {
        (available / videoHeightPx).coerceAtLeast(MIN_SHEET_FIT_SCALE)
    } else {
        1f
    }
    return SheetFitTransform(scale = scale, shiftY = -sheetHeightPx / 2f)
}
