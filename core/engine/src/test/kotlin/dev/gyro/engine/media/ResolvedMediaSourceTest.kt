package dev.gyro.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedMediaSourceTest {

    @Test
    fun pathSource_returnsTheSameUrlEveryTime() {
        val source = ResolvedMediaSource("/sdcard/Movies/a.mkv")
        assertEquals("/sdcard/Movies/a.mkv", source.takeTargetUrl())
        assertEquals("/sdcard/Movies/a.mkv", source.takeTargetUrl())
        source.close()
        assertEquals("/sdcard/Movies/a.mkv", source.takeTargetUrl())
    }

    @Test
    fun close_isIdempotent() {
        val source = ResolvedMediaSource("content://media/external/video/1")
        source.close()
        source.close()
        // A closed source can still be tried by path; mpv reports the failure itself.
        assertEquals("content://media/external/video/1", source.takeTargetUrl())
    }
}
