package dev.gyro.feature.player

/**
 * Displacement of the persistent video surface from the page that owns it, in pages.
 *
 * The pager reports its position as `currentPage + currentPageOffsetFraction`, which is the only
 * quantity that stays continuous while a page flips: the fraction changes sign as `currentPage`
 * steps (0 + 0.5 then 1 + -0.5), so reading the fraction alone jumps a full page mid-drag.
 *
 * The owning page must come from the pager itself ([settledPage]), never from the playback index.
 * That index round-trips through `settledPage -> ViewModel -> recomposition`, and while it lags the
 * surface sits at `owner - scrollPos ~ -1`, which parks it a full page off-screen: the compositor
 * reports a 48px-tall visible region and the user sees black while the engine keeps presenting.
 *
 * A playlist-less single-file VIEW intent needs no special case: the pager has one page, so
 * `settledPage` is that page and the surface stays centred.
 */
internal fun videoSurfacePageOffset(
    currentPage: Int,
    pageOffsetFraction: Float,
    settledPage: Int,
): Float {
    val scrollPos = currentPage + pageOffsetFraction
    return (settledPage - scrollPos).coerceIn(-1f, 1f)
}
