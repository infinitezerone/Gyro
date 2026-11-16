package dev.gyro.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSurfaceOffsetTest {

    private val delta = 1e-4f

    @Test
    fun restingOnTheSettledPage_isCentred() {
        assertEquals(0f, videoSurfacePageOffset(0, 0f, 0), delta)
        assertEquals(0f, videoSurfacePageOffset(3, 0f, 3), delta)
    }

    @Test
    fun upwardSwipe_movesTheSurfaceOffTheTop() {
        // Dragging from page 0 towards page 1: the surface must travel up, i.e. negative.
        assertEquals(-0.25f, videoSurfacePageOffset(0, 0.25f, 0), delta)
        assertEquals(-0.5f, videoSurfacePageOffset(0, 0.5f, 0), delta)
        // Past the halfway point the pager steps to page 1 and the fraction flips sign. The sum is
        // what stays continuous; reading the fraction alone would jump a whole page here.
        assertEquals(-0.5f, videoSurfacePageOffset(1, -0.5f, 0), delta)
        assertEquals(-0.9f, videoSurfacePageOffset(1, -0.1f, 0), delta)
    }

    @Test
    fun settlingOnTheNextPage_recentres() {
        // The pager's own settled page follows the drag, so the surface returns to 0 by itself.
        assertEquals(0f, videoSurfacePageOffset(1, 0f, 1), delta)
    }

    @Test
    fun downwardSwipe_movesTheSurfaceOffTheBottom() {
        // Dragging back from page 1 towards page 0: the fraction goes negative, the surface must
        // travel down, i.e. positive.
        assertEquals(0.5f, videoSurfacePageOffset(1, -0.5f, 1), delta)
        assertEquals(0.5f, videoSurfacePageOffset(0, 0.5f, 1), delta)
        assertEquals(0.9f, videoSurfacePageOffset(0, 0.1f, 1), delta)
    }

    @Test
    fun staleOwner_neverParksTheSurfaceMoreThanOnePageAway() {
        // Whatever the pager reports, the offset stays bounded. An unbounded value translates the
        // surface a full page (or more) off-screen, which reads as a black video while the engine
        // keeps presenting frames.
        assertEquals(-1f, videoSurfacePageOffset(3, 0f, 0), delta)
        assertEquals(-1f, videoSurfacePageOffset(4, 0.5f, 0), delta)
        assertEquals(1f, videoSurfacePageOffset(0, 0f, 3), delta)
    }

    @Test
    fun singlePagePlaylist_staysCentred() {
        // A playlist-less VIEW intent has exactly one page, so the surface never offsets.
        assertEquals(0f, videoSurfacePageOffset(0, 0f, 0), delta)
        assertEquals(-0.3f, videoSurfacePageOffset(0, 0.3f, 0), delta)
    }
}
