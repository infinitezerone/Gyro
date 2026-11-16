package dev.gyro.feature.player.component

/**
 * Result of resolving a scrub position against the chapter markers.
 *
 * [fraction] is the position to display; [chapterIndex] is the marker it snapped to, or -1 when
 * the finger was outside every snap radius. Keeping the index separate lets the caller fire haptic
 * feedback only when the finger crosses onto a *different* chapter, not on every drag event.
 */
internal data class ChapterSnap(
    val fraction: Float,
    val chapterIndex: Int
)

/**
 * Pulls the scrub position onto the closest chapter marker when the finger is within [radiusPx].
 * Chapters are 0..1 fractions, converted to pixels here so the snap radius stays the same physical
 * size regardless of bar width or screen density.
 */
internal fun snapToChapterResult(
    xPx: Float,
    widthPx: Float,
    chapters: List<Float>,
    radiusPx: Float
): ChapterSnap {
    val raw = (xPx / widthPx).coerceIn(0f, 1f)
    if (chapters.isEmpty() || radiusPx <= 0f) return ChapterSnap(raw, -1)

    var closestIndex = -1
    var bestDistance = radiusPx
    for ((index, frac) in chapters.withIndex()) {
        val distance = kotlin.math.abs(frac * widthPx - xPx)
        if (distance <= bestDistance) {
            bestDistance = distance
            closestIndex = index
        }
    }
    return if (closestIndex >= 0) {
        ChapterSnap(chapters[closestIndex], closestIndex)
    } else {
        ChapterSnap(raw, -1)
    }
}

/** Convenience wrapper returning just the fraction, kept for callers that do not need haptics. */
internal fun snapToChapter(
    xPx: Float,
    widthPx: Float,
    chapters: List<Float>,
    radiusPx: Float
): Float = snapToChapterResult(xPx, widthPx, chapters, radiusPx).fraction
