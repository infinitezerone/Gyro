package dev.gyro.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun descriptorSource_detachesOnceAndDoesNotCloseAfterHandOff() {
        var detachCalls = 0
        var closeCalls = 0
        val source = ResolvedMediaSource(
            targetPathOrUri = "content://media/external/video/42",
            descriptor = object : NativeFileDescriptor {
                override fun detachFd(): Int {
                    detachCalls += 1
                    return 42
                }

                override fun close() {
                    closeCalls += 1
                }
            }
        )

        assertEquals("fdclose://42", source.takeTargetUrl())
        assertEquals("fdclose://42", source.takeTargetUrl())
        assertEquals(1, detachCalls)

        source.close()
        assertEquals("Detached fd is owned by mpv and must not be closed by ResolvedMediaSource", 0, closeCalls)
    }

    @Test
    fun descriptorSource_closedBeforeHandOffClosesDescriptorOnce() {
        var detachCalls = 0
        var closeCalls = 0
        val source = ResolvedMediaSource(
            targetPathOrUri = "content://media/external/video/99",
            descriptor = object : NativeFileDescriptor {
                override fun detachFd(): Int {
                    detachCalls += 1
                    return 99
                }

                override fun close() {
                    closeCalls += 1
                }
            }
        )

        source.close()
        source.close()
        assertEquals(1, closeCalls)
        assertEquals(0, detachCalls)
        assertEquals("content://media/external/video/99", source.takeTargetUrl())
        assertEquals(0, detachCalls)
    }
}
