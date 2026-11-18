package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.TestUri
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultThumbnailRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context

    @Before
    fun setUp() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { contentResolver } returns resolver
        }
        mockkConstructor(MediaMetadataRetriever::class)
        every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<Context>(), any()) } just Runs
        every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun getThumbnail_bucketsTimeToOneSecondGranularity() = runTest(testDispatcher) {
        val fakeBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { allocationByteCount } returns 4096
        }
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                12_000_000L,
                MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                240,
                136,
            )
        } returns fakeBitmap

        val repository = DefaultThumbnailRepository(context, backgroundScope, testDispatcher, testDispatcher)
        val uri = TestUri("content://media/external/video/media/1")

        val result = repository.getThumbnail(uri, timeMs = 12_999L)

        assertSame(fakeBitmap, result)
        verify(exactly = 1) {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                12_000_000L,
                MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                240,
                136,
            )
        }
    }

    @Test
    fun getArtwork_contentUriLoadThumbnailThrows_fallsBackToMediaMetadataRetriever() = runTest(testDispatcher) {
        val uri = TestUri("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/ep01.mkv")
        val fallbackBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { allocationByteCount } returns 8192
        }
        every { resolver.loadThumbnail(uri, any(), isNull()) } throws IOException("Unsupported SAF thumbnail")
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                180,
            )
        } returns fallbackBitmap

        val repository = DefaultThumbnailRepository(context, backgroundScope, testDispatcher, testDispatcher)
        val result = repository.getArtwork(uri, width = 320, height = 180)

        assertSame(fallbackBitmap, result)
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().setDataSource(context, uri) }
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }

    @Test
    fun getArtwork_contentUriLoadThumbnailReturnsNull_fallsBackToMediaMetadataRetriever() = runTest(testDispatcher) {
        val uri = TestUri("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/ep02.mkv")
        val fallbackBitmap = mockk<Bitmap>(relaxed = true) {
            every { isRecycled } returns false
            every { allocationByteCount } returns 8192
        }
        every<Bitmap?> { resolver.loadThumbnail(uri, any(), isNull()) } returns null
        every {
            anyConstructed<MediaMetadataRetriever>().getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                180,
            )
        } returns fallbackBitmap

        val repository = DefaultThumbnailRepository(context, backgroundScope, testDispatcher, testDispatcher)
        val result = repository.getArtwork(uri, width = 320, height = 180)

        assertSame(fallbackBitmap, result)
        verify(exactly = 1) { anyConstructed<MediaMetadataRetriever>().release() }
    }
}
