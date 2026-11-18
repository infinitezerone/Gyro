package dev.gyro.engine.media

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.gyro.engine.api.media.MediaItem
import dev.gyro.engine.model.PlaybackSourceOpenException
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            val asBase: PlaybackSourceOpenException = expected
            assertNotNull(asBase.cause)
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

    @Test(timeout = 5_000)
    fun networkAndSpecialSchemeUris_preserveFullTargetIntact() = runBlocking {
        val pureResolver = DefaultMediaResourceResolver()
        val targets = listOf(
            "https://example.com/live.m3u8?token=1",
            "http://192.168.1.10:8080/video.mp4?quality=high&auth=abc",
            "rtsp://cam.local:554/stream?channel=1",
            "rtmp://live.example.com/app/streamkey",
            "fdclose://42",
        )

        for (url in targets) {
            val uriItem = MediaItem(uri = TestUri(url))
            val targetItem = MediaItem(target = url)

            assertEquals(url, pureResolver.resolve(uriItem).takeTargetUrl())
            assertEquals(url, pureResolver.resolve(targetItem).takeTargetUrl())
            assertEquals(url, MediaResourceResolver.Direct.resolve(uriItem).takeTargetUrl())
            assertEquals(url, MediaResourceResolver.Direct.resolve(targetItem).takeTargetUrl())
        }
    }

    @Test(timeout = 5_000)
    fun nullSchemeUriAndFileUri_extractPosixPath() = runBlocking {
        val pureResolver = DefaultMediaResourceResolver()
        val nullSchemeUri = mockk<Uri> {
            every { scheme } returns null
            every { path } returns "/sdcard/Movies/null_scheme.mkv"
        }
        val nullSchemeItem = MediaItem(uri = nullSchemeUri, target = "/sdcard/Movies/null_scheme.mkv")
        assertEquals("/sdcard/Movies/null_scheme.mkv", pureResolver.resolve(nullSchemeItem).takeTargetUrl())
        assertEquals("/sdcard/Movies/null_scheme.mkv", MediaResourceResolver.Direct.resolve(nullSchemeItem).takeTargetUrl())

        val upperFileItem = MediaItem(uri = TestUri("FILE:///sdcard/Movies/ep3.mkv"))
        assertEquals("/sdcard/Movies/ep3.mkv", pureResolver.resolve(upperFileItem).takeTargetUrl())
        assertEquals("/sdcard/Movies/ep3.mkv", MediaResourceResolver.Direct.resolve(upperFileItem).takeTargetUrl())
    }

    @Test(timeout = 5_000)
    fun contentTargetWithoutUri_parsesUriAndOpensDescriptor() = runBlocking {
        val contentResolver = mockk<ContentResolver>()
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true) {
            every { detachFd() } returns 77
        }
        val expectedUri = TestUri("content://media/external/video/77")
        every { contentResolver.openFileDescriptor(expectedUri, "r") } returns pfd

        val contextWithResolver: Context = object : ContextWrapper(null) {
            override fun getContentResolver(): ContentResolver = contentResolver
        }

        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } answers { TestUri(firstArg()) }
            val resolved = DefaultMediaResourceResolver(contextWithResolver)
                .resolve(MediaItem(target = "content://media/external/video/77"))
            assertEquals("fdclose://77", resolved.takeTargetUrl())
            verify(exactly = 1) { contentResolver.openFileDescriptor(expectedUri, "r") }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test(timeout = 5_000)
    fun contentTargetWithoutUri_withoutContentResolverFailsLoudly() = runBlocking {
        try {
            MediaResourceResolver.Direct.resolve(MediaItem(target = "content://media/external/video/1"))
            fail("Direct resolver must reject content:// targets")
        } catch (_: MediaResourceResolver.MediaOpenException) {
            // Expected
        }

        try {
            resolver.resolve(MediaItem(target = "content://media/external/video/1"))
            fail("DefaultMediaResourceResolver must reject unopenable content:// string targets")
        } catch (_: MediaResourceResolver.MediaOpenException) {
            // Expected
        }
    }

    /** A context whose base is null, so every content resolver access throws. */
    private class HostileContext : ContextWrapper(null)
}
