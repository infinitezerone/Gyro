package dev.gyro.engine.media

import android.net.TestUri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class MediaResourceResolverTest {

    // TestUri reports scheme "content", which is the only branch a JVM test can drive: the file and
    // bare-path branches depend on real Uri parsing and are covered end to end in LoadRequestTest.
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

    /** A context whose base is null, so every content resolver access throws. */
    private class HostileContext : android.content.ContextWrapper(null)
}
