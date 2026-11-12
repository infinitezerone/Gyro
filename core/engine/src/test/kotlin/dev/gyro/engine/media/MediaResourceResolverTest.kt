package dev.gyro.engine.media

import android.net.TestUri
import dev.gyro.engine.api.media.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MediaResourceResolverTest {

    private val resolver = DefaultMediaResourceResolver(HostileContext())

    @Test(timeout = 5_000)
    fun contentUri_thatCannotBeOpenedFailsLoudly() = runBlocking {
        try {
            resolver.resolve(MediaItem(TestUri("content://media/external/video/1")))
            fail("a content URI without an openable descriptor must not resolve silently")
        } catch (expected: MediaResourceResolver.MediaOpenException) {
            // The engine turns this into PlaybackState.ERROR instead of a black screen.
        }
    }

    @Test(timeout = 5_000)
    fun fileUriAndBarePath_resolveToPosixPathWithoutContext() = runBlocking {
        val pureResolver = DefaultMediaResourceResolver()
        val fromFileScheme = pureResolver.resolve(MediaItem(target = "file:///sdcard/Movies/ep1.mkv"))
        assertEquals("/sdcard/Movies/ep1.mkv", fromFileScheme.takeTargetUrl())

        val fromBarePath = pureResolver.resolve(MediaItem(target = "/sdcard/Movies/ep2.mkv"))
        assertEquals("/sdcard/Movies/ep2.mkv", fromBarePath.takeTargetUrl())
    }

    /** A context whose base is null, so every content resolver access throws. */
    private class HostileContext : android.content.ContextWrapper(null)
}
