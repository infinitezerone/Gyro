package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.TestUri
import android.provider.MediaStore
import dev.gyro.core.model.VideoMediaItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultMediaRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun getVideos_filtersPendingAndDeduplicatesByUriAndNameSize() = runTest(testDispatcher) {
        val collectionUri = TestUri("content://media/external/video/media")
        mockkStatic(MediaStore.Video.Media::class)
        mockkStatic(ContentUris::class)
        every { MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) } returns collectionUri
        every {
            ContentUris.withAppendedId(any(), any())
        } answers {
            TestUri("content://media/external/video/media/${secondArg<Long>()}")
        }

        val mediaStoreCursor = fakeMediaCursor(
            listOf(
                arrayOf(1L, "ep01.mkv", 120_000L, 500_000L, 1920, 1080, 1000L, "Anime", "video/x-matroska"),
            )
        )
        val resolver = mockk<ContentResolver>(relaxed = true)
        every {
            resolver.query(
                collectionUri,
                any(),
                "${MediaStore.Video.Media.IS_PENDING} = 0",
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )
        } returns mediaStoreCursor

        val context = mockk<Context>(relaxed = true) {
            every { contentResolver } returns resolver
        }

        val customFolderRepo = mockk<CustomFolderRepository>()
        coEvery { customFolderRepo.scanCustomFolders() } returns listOf(
            // Same URI as MediaStore entry -> excluded
            VideoMediaItem(
                id = 10L,
                uriString = "content://media/external/video/media/1",
                displayName = "ep01_renamed.mkv",
                durationMs = 0L,
                sizeBytes = 999L,
                width = 0,
                height = 0,
                dateModified = 1000L,
                folderName = "Custom",
                mimeType = "video/x-matroska",
            ),
            // Same (displayName, sizeBytes) as MediaStore entry via SAF URI -> excluded
            VideoMediaItem(
                id = 11L,
                uriString = "content://com.android.externalstorage.documents/tree/primary%3AAnime/document/ep01.mkv",
                displayName = "ep01.mkv",
                durationMs = 0L,
                sizeBytes = 500_000L,
                width = 0,
                height = 0,
                dateModified = 1000L,
                folderName = "Anime",
                mimeType = "video/x-matroska",
            ),
            // Unique custom item -> included
            VideoMediaItem(
                id = 12L,
                uriString = "content://com.android.externalstorage.documents/tree/primary%3AAnime/document/ova.mkv",
                displayName = "ova.mkv",
                durationMs = 0L,
                sizeBytes = 300_000L,
                width = 0,
                height = 0,
                dateModified = 2000L,
                folderName = "Anime",
                mimeType = "video/x-matroska",
            ),
            // Duplicate custom item from overlapping SAF tree -> excluded
            VideoMediaItem(
                id = 13L,
                uriString = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/ova.mkv",
                displayName = "ova.mkv",
                durationMs = 0L,
                sizeBytes = 300_000L,
                width = 0,
                height = 0,
                dateModified = 2000L,
                folderName = "Movies",
                mimeType = "video/x-matroska",
            ),
        )

        val repository = DefaultMediaRepository(context, testDispatcher, customFolderRepo)
        val videos = repository.getVideos()

        assertEquals(listOf("ep01.mkv", "ova.mkv"), videos.map { it.displayName })
        verify(exactly = 1) {
            resolver.query(
                collectionUri,
                any(),
                "${MediaStore.Video.Media.IS_PENDING} = 0",
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
            )
        }
    }

    private fun fakeMediaCursor(rows: List<Array<Any?>>): Cursor {
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
        )
        var rowIndex = -1
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(any()) } answers { columns.indexOf(firstArg()) }
        every { cursor.moveToNext() } answers {
            rowIndex++
            rowIndex in rows.indices
        }
        every { cursor.getString(any()) } answers { rows[rowIndex][firstArg<Int>()] as? String }
        every { cursor.getLong(any()) } answers { (rows[rowIndex][firstArg<Int>()] as? Number)?.toLong() ?: 0L }
        every { cursor.getInt(any()) } answers { (rows[rowIndex][firstArg<Int>()] as? Number)?.toInt() ?: 0 }
        return cursor
    }
}
