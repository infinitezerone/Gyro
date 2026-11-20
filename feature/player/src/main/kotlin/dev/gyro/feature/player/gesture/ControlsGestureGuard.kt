package dev.gyro.feature.player.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.gyro.feature.player.component.LandscapeActionButtonSize
import dev.gyro.feature.player.component.LandscapeBarHorizontalExtra
import dev.gyro.feature.player.component.LandscapeBottomBarBottomPadding
import dev.gyro.feature.player.component.LandscapeTopBarTopPadding
import dev.gyro.feature.player.component.PortraitActionButtonSize
import dev.gyro.feature.player.component.PortraitBarBottomPadding
import dev.gyro.feature.player.component.PortraitControlRowHeight
import dev.gyro.feature.player.component.PortraitScrubRowHeight
import dev.gyro.feature.player.component.PortraitSideRowBottomPadding
import dev.gyro.feature.player.component.PortraitSideRowHeight
import dev.gyro.feature.player.component.PortraitSideRowHorizontalPadding
import kotlin.math.abs

private const val TOP_GUARD_BUFFER_DP = 24f
private const val LANDSCAPE_BOTTOM_GUARD_BUFFER_DP = 30f
private const val PORTRAIT_BOTTOM_GUARD_BUFFER_DP = 32f
private const val SIDE_BUTTON_GUARD_BUFFER_DP = 16f
private const val LANDSCAPE_TOP_BAR_ROW_HEIGHT_DP = 44f
private const val LANDSCAPE_TOP_BAR_BOTTOM_PADDING_DP = 4f
private const val LANDSCAPE_BOTTOM_CONTROL_ROW_HEIGHT_DP = 48f
private const val LANDSCAPE_BOTTOM_SPACER_HEIGHT_DP = 2f
private const val LANDSCAPE_SCRUB_BAR_ESTIMATED_HEIGHT_DP = 24f
private const val PORTRAIT_TOP_BAR_ROW_HEIGHT_DP = 48f
private const val PORTRAIT_TOP_BAR_VERTICAL_PADDING_DP = 4f

/**
 * Pixel bounds defining the brightness/volume swipe exclusion zones when the player control
 * overlay is visible.
 */
data class ControlsGestureGuardMetrics(
    val landscapeTopGuardPx: Float = 0f,
    val landscapeBottomGuardPx: Float = 0f,
    val landscapeSideGuardWidthPx: Float = 0f,
    val landscapeSideGuardHalfHeightPx: Float = 0f,
    val portraitTopGuardPx: Float = 0f,
    val portraitBottomGuardPx: Float = 0f,
    val portraitSideGuardWidthPx: Float = 0f,
    val portraitSideGuardTopFromBottomPx: Float = 0f,
    val portraitSideGuardBottomFromBottomPx: Float = 0f,
) {
    companion object {
        val None = ControlsGestureGuardMetrics()
    }
}

/**
 * Computes pixel guard-zone dimensions aligned with `PlayerControlsOverlay`, `PlayerTopBar`,
 * and `PlayerBottomBar` geometry plus a buffer around touchable controls and the scrub bar.
 */
internal fun computeControlsGestureGuardMetrics(
    density: Float,
    statusBarTopPx: Int,
    cutoutTopPx: Int,
    cutoutLeftPx: Int,
    cutoutRightPx: Int,
    navBarBottomPx: Int,
    landscapeHorizontalInsetDp: Float,
): ControlsGestureGuardMetrics {
    val safeDensity = if (density.isFinite() && density > 0f) density else 1f
    val safeStatusBarTopPx = statusBarTopPx.coerceAtLeast(0).toFloat()
    val safeCutoutTopPx = cutoutTopPx.coerceAtLeast(0).toFloat()
    val safeCutoutLeftPx = cutoutLeftPx.coerceAtLeast(0).toFloat()
    val safeCutoutRightPx = cutoutRightPx.coerceAtLeast(0).toFloat()
    val safeNavBarBottomPx = navBarBottomPx.coerceAtLeast(0).toFloat()
    val safeLandscapeInsetDp =
        if (landscapeHorizontalInsetDp.isFinite() && landscapeHorizontalInsetDp >= 0f) {
            landscapeHorizontalInsetDp
        } else {
            0f
        }

    val landscapeTopBarHeightDp =
        LandscapeTopBarTopPadding.value + LANDSCAPE_TOP_BAR_ROW_HEIGHT_DP + LANDSCAPE_TOP_BAR_BOTTOM_PADDING_DP
    val landscapeTopGuardPx =
        safeStatusBarTopPx + (landscapeTopBarHeightDp + TOP_GUARD_BUFFER_DP) * safeDensity

    val landscapeBottomBarHeightDp =
        LandscapeBottomBarBottomPadding.value +
            LANDSCAPE_BOTTOM_CONTROL_ROW_HEIGHT_DP +
            LANDSCAPE_BOTTOM_SPACER_HEIGHT_DP +
            LANDSCAPE_SCRUB_BAR_ESTIMATED_HEIGHT_DP
    val landscapeBottomGuardPx =
        (landscapeBottomBarHeightDp + LANDSCAPE_BOTTOM_GUARD_BUFFER_DP) * safeDensity

    val landscapeSideGuardWidthPx =
        (safeLandscapeInsetDp + LandscapeBarHorizontalExtra.value + LandscapeActionButtonSize.value + SIDE_BUTTON_GUARD_BUFFER_DP) *
            safeDensity
    val landscapeSideGuardHalfHeightPx =
        (LandscapeActionButtonSize.value / 2f + SIDE_BUTTON_GUARD_BUFFER_DP) * safeDensity

    val portraitTopBarHeightDp =
        PORTRAIT_TOP_BAR_VERTICAL_PADDING_DP + PORTRAIT_TOP_BAR_ROW_HEIGHT_DP + PORTRAIT_TOP_BAR_VERTICAL_PADDING_DP
    val portraitTopGuardPx =
        maxOf(safeStatusBarTopPx, safeCutoutTopPx) + (portraitTopBarHeightDp + TOP_GUARD_BUFFER_DP) * safeDensity

    val portraitBottomMainHeightDp =
        PortraitBarBottomPadding.value + PortraitControlRowHeight.value + PortraitScrubRowHeight.value
    val portraitBottomGuardPx =
        safeNavBarBottomPx + (portraitBottomMainHeightDp + PORTRAIT_BOTTOM_GUARD_BUFFER_DP) * safeDensity

    val portraitSideGuardWidthPx =
        maxOf(safeCutoutLeftPx, safeCutoutRightPx) +
            (PortraitSideRowHorizontalPadding.value + PortraitActionButtonSize.value + SIDE_BUTTON_GUARD_BUFFER_DP) *
            safeDensity
    val sideRowBottomDp = portraitBottomMainHeightDp + PortraitSideRowBottomPadding.value
    val sideRowTopDp = sideRowBottomDp + PortraitSideRowHeight.value
    val portraitSideGuardBottomFromBottomPx =
        safeNavBarBottomPx + (sideRowBottomDp - SIDE_BUTTON_GUARD_BUFFER_DP).coerceAtLeast(0f) * safeDensity
    val portraitSideGuardTopFromBottomPx =
        safeNavBarBottomPx + (sideRowTopDp + SIDE_BUTTON_GUARD_BUFFER_DP) * safeDensity

    return ControlsGestureGuardMetrics(
        landscapeTopGuardPx = landscapeTopGuardPx,
        landscapeBottomGuardPx = landscapeBottomGuardPx,
        landscapeSideGuardWidthPx = landscapeSideGuardWidthPx,
        landscapeSideGuardHalfHeightPx = landscapeSideGuardHalfHeightPx,
        portraitTopGuardPx = portraitTopGuardPx,
        portraitBottomGuardPx = portraitBottomGuardPx,
        portraitSideGuardWidthPx = portraitSideGuardWidthPx,
        portraitSideGuardTopFromBottomPx = portraitSideGuardTopFromBottomPx,
        portraitSideGuardBottomFromBottomPx = portraitSideGuardBottomFromBottomPx,
    )
}

/**
 * Returns true if a pointer-down at [downPosition] falls inside a guard zone around visible
 * player controls, where vertical brightness/volume swipes should be suppressed to prevent
 * accidental adjustments.
 */
internal fun isInControlsBrightnessVolumeGuardZone(
    downPosition: Offset,
    containerSize: IntSize,
    controlsVisible: Boolean,
    metrics: ControlsGestureGuardMetrics,
): Boolean {
    if (!controlsVisible || containerSize.width <= 0 || containerSize.height <= 0) {
        return false
    }
    if (!downPosition.x.isFinite() || !downPosition.y.isFinite()) {
        return false
    }

    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    val isPortrait = containerSize.width < containerSize.height

    return if (isPortrait) {
        val inTopGuard = metrics.portraitTopGuardPx > 0f && downPosition.y < metrics.portraitTopGuardPx
        val inBottomGuard = metrics.portraitBottomGuardPx > 0f && downPosition.y > h - metrics.portraitBottomGuardPx
        val inSideButtonGuard =
            metrics.portraitSideGuardWidthPx > 0f &&
                metrics.portraitSideGuardTopFromBottomPx > metrics.portraitSideGuardBottomFromBottomPx &&
                (downPosition.x < metrics.portraitSideGuardWidthPx || downPosition.x > w - metrics.portraitSideGuardWidthPx) &&
                downPosition.y in (h - metrics.portraitSideGuardTopFromBottomPx)..(h - metrics.portraitSideGuardBottomFromBottomPx)
        inTopGuard || inBottomGuard || inSideButtonGuard
    } else {
        val inTopGuard = metrics.landscapeTopGuardPx > 0f && downPosition.y < metrics.landscapeTopGuardPx
        val inBottomGuard = metrics.landscapeBottomGuardPx > 0f && downPosition.y > h - metrics.landscapeBottomGuardPx
        val inSideButtonGuard =
            metrics.landscapeSideGuardWidthPx > 0f &&
                metrics.landscapeSideGuardHalfHeightPx > 0f &&
                (downPosition.x < metrics.landscapeSideGuardWidthPx || downPosition.x > w - metrics.landscapeSideGuardWidthPx) &&
                abs(downPosition.y - h / 2f) <= metrics.landscapeSideGuardHalfHeightPx
        inTopGuard || inBottomGuard || inSideButtonGuard
    }
}
