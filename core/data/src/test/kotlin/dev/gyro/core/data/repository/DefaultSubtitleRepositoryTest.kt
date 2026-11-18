package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.TestUri
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSubtitleRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var repository: DefaultSubtitleRepository

    @Before
    fun setUp() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { contentResolver } returns resolver
        }
        repository = DefaultSubtitleRepository(context, testDispatcher)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun findCompanionSubtitles_fileScheme_matchesValidExtensionsAndNamingOnly() = runTest(testDispatcher) {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers { TestUri("file://${firstArg<File>().absolutePath}") }

        val dir = tempFolder.newFolder("anime")
        val video = File(dir, "ep01.mkv").apply { createNewFile() }
        val exactSrt = File(dir, "ep01.srt").apply { createNewFile() }
        val langAss = File(dir, "ep01.zh.ass").apply { createNewFile() }
        val underVtt = File(dir, "ep01_en.vtt").apply { createNewFile() }
        File(dir, "ep010.srt").createNewFile()
        File(dir, "other.srt").createNewFile()
        File(dir, "ep01.txt").createNewFile()
        File(dir, "ep01.srt.dir").mkdirs()

        val found = repository.findCompanionSubtitles(TestUri("file://${video.absolutePath}"), "ep01.mkv")

        assertEquals(
            setOf(
                "file://${exactSrt.absolutePath}",
                "file://${langAss.absolutePath}",
                "file://${underVtt.absolutePath}",
            ),
            found.map { it.toString() }.toSet(),
        )
    }

    @Test
    fun findCompanionSubtitles_singleFileOpenDocument_doesNotThrow() = runTest(testDispatcher) {
        val openDocUri = TestUri("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fep01.mkv")
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isTreeUri(openDocUri) } returns false
        every { DocumentsContract.isDocumentUri(context, openDocUri) } returns true
        every { DocumentsContract.getTreeDocumentId(any()) } throws IllegalArgumentException("Not a tree URI")

        val found = repository.findCompanionSubtitles(openDocUri, "ep01.mkv")

        assertTrue(found.isEmpty())
        verify(exactly = 0) { DocumentsContract.getTreeDocumentId(any()) }
    }

    @Test
    fun findCompanionSubtitles_safRootLevelTreeDocId_resolvesRootParent() = runTest(testDispatcher) {
        val videoUri = TestUri("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Aep01.mkv")
        val childrenUri = TestUri("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3A/children")
        val subUri = TestUri("content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3Aep01.ass")

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isTreeUri(videoUri) } returns true
        every { DocumentsContract.isDocumentUri(context, videoUri) } returns true
        every { DocumentsContract.getDocumentId(videoUri) } returns "primary:ep01.mkv"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(videoUri, "primary:") } returns childrenUri
        every { DocumentsContract.buildDocumentUriUsingTree(videoUri, "primary:ep01.ass") } returns subUri

        val cursor = fakeCursor(
            columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            rows = listOf(
                arrayOf("primary:ep01.ass", "ep01.ass"),
                arrayOf("primary:ep02.ass", "ep02.ass"),
            ),
        )
        every { resolver.query(childrenUri, any(), null, null, null) } returns cursor

        val found = repository.findCompanionSubtitles(videoUri, videoDisplayName = null)

        assertEquals(listOf(subUri), found)
        verify(exactly = 1) { DocumentsContract.buildChildDocumentsUriUsingTree(videoUri, "primary:") }
    }

    @Test
    fun findCompanionSubtitles_mediaStoreUri_queriesRelativePathAndReturnsContentUris() = runTest(testDispatcher) {
        val videoUri = TestUri("content://media/external/video/media/42")
        val filesCollection = TestUri("content://media/external/file")
        val expectedSubUri = TestUri("content://media/external/file/99")

        mockkStatic(MediaStore.Files::class)
        mockkStatic(ContentUris::class)
        every { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) } returns filesCollection
        every { ContentUris.withAppendedId(filesCollection, 99L) } returns expectedSubUri

        @Suppress("DEPRECATION")
        val videoCursor = fakeCursor(
            columns = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
            ),
            rows = listOf(arrayOf("Movies/Anime/", "ep01.mkv", null)),
        )
        val filesCursor = fakeCursor(
            columns = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
            ),
            rows = listOf(
                arrayOf(99L, "ep01.chs.srt"),
                arrayOf(100L, "ep02.chs.srt"),
                arrayOf(101L, "ep01.mp4"),
            ),
        )
        every { resolver.query(videoUri, any(), null, null, null) } returns videoCursor
        every {
            resolver.query(
                filesCollection,
                any(),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("Movies/Anime/"),
                null,
            )
        } returns filesCursor

        val found = repository.findCompanionSubtitles(videoUri, videoDisplayName = null)

        assertEquals(listOf(expectedSubUri), found)
    }

    @Test
    fun findCompanionSubtitles_mediaStoreWithoutRelativePath_fallsBackToDataParent() = runTest(testDispatcher) {
        val videoUri = TestUri("content://media/external/video/media/7")
        val filesCollection = TestUri("content://media/external/file")
        val expectedSubUri = TestUri("content://media/external/file/55")

        mockkStatic(MediaStore.Files::class)
        mockkStatic(ContentUris::class)
        every { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) } returns filesCollection
        every { ContentUris.withAppendedId(filesCollection, 55L) } returns expectedSubUri

        @Suppress("DEPRECATION")
        val dataCol = MediaStore.MediaColumns.DATA
        val videoCursor = fakeCursor(
            columns = arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                dataCol,
            ),
            rows = listOf(arrayOf(null, "movie.mkv", "/storage/emulated/0/Movies/movie.mkv")),
        )
        val filesCursor = fakeCursor(
            columns = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                dataCol,
            ),
            rows = listOf(
                arrayOf(55L, "movie.srt", "/storage/emulated/0/Movies/movie.srt"),
                arrayOf(56L, "movie.srt", "/storage/emulated/0/Movies/Subdir/movie.srt"),
            ),
        )
        every { resolver.query(videoUri, any(), null, null, null) } returns videoCursor
        every {
            resolver.query(
                filesCollection,
                any(),
                "$dataCol LIKE ?",
                arrayOf("/storage/emulated/0/Movies/%"),
                null,
            )
        } returns filesCursor

        val found = repository.findCompanionSubtitles(videoUri, videoDisplayName = null)

        assertEquals(listOf(expectedSubUri), found)
    }

    private fun fakeCursor(
        columns: Array<String>,
        rows: List<Array<Any?>>,
    ): Cursor {
        var rowIndex = -1
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndex(any()) } answers {
            columns.indexOf(firstArg())
        }
        every { cursor.moveToFirst() } answers {
            rowIndex = 0
            rows.isNotEmpty()
        }
        every { cursor.moveToNext() } answers {
            rowIndex++
            rowIndex in rows.indices
        }
        every { cursor.getString(any()) } answers {
            rows[rowIndex][firstArg<Int>()] as? String
        }
        every { cursor.getLong(any()) } answers {
            (rows[rowIndex][firstArg<Int>()] as? Number)?.toLong() ?: 0L
        }
        return cursor
    }
}
