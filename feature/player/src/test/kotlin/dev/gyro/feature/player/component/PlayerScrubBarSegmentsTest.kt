package dev.gyro.feature.player.component

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerScrubBarSegmentsTest {

    @Test
    fun emptyChaptersProduceSingleFullSegment() {
        assertEquals(listOf(0f to 1f), buildScrubBarSegments(emptyList()))
    }

    @Test
    fun sortedChaptersProduceContiguousSegments() {
        val segments = buildScrubBarSegments(listOf(0.2f, 0.5f, 0.9f))
        assertEquals(
            listOf(
                0f to 0.2f,
                0.2f to 0.5f,
                0.5f to 0.9f,
                0.9f to 1f
            ),
            segments
        )
    }

    @Test
    fun boundaryUnsortedAndNearDuplicateSplitsAreSanitized() {
        val segments = buildScrubBarSegments(
            listOf(-0.1f, 0f, 0.7f, 0.300f, 0.302f, 1f, 1.2f)
        )
        assertEquals(
            listOf(
                0f to 0.300f,
                0.300f to 0.7f,
                0.7f to 1f
            ),
            segments
        )
    }
}
