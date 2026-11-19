package dev.gyro.feature.home.thumbnail

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.request.Options
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ContentThumbnailFetcherTest {

    private val context: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val imageLoader: ImageLoader = mockk(relaxed = true)
    private val options: Options = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { context.contentResolver } returns contentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun factory_returnsNullForNonContentUri() {
        val fileUri = mockk<Uri> {
            every { scheme } returns "file"
        }
        val factory = ContentThumbnailFetcher.Factory(context)

        val fetcher = factory.create(fileUri, options, imageLoader)
        assertNull(fetcher)
    }

    @Test
    fun factory_createsFetcherForContentUri() {
        val contentUri = mockk<Uri> {
            every { scheme } returns "content"
        }
        val factory = ContentThumbnailFetcher.Factory(context)

        val fetcher = factory.create(contentUri, options, imageLoader)
        assertNotNull(fetcher)
    }

    @Test
    fun fetch_fallsBackToNullWhenContentResolverThrows() = runTest {
        val contentUri = mockk<Uri> {
            every { scheme } returns "content"
        }
        every { contentResolver.loadThumbnail(any(), any(), any()) } throws RuntimeException("Decoder failure")

        val fetcher = ContentThumbnailFetcher(context, contentUri, options)
        val result = fetcher.fetch()

        // 应当静默捕获异常并返回 null，由下游的 VideoFrameDecoder 接管
        assertNull(result)
    }
}
