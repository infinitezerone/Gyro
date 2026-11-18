package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.TestUri
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCustomFolderRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun scanCustomFolders_deduplicatesByUriStringAcrossTrees() = runTest(testDispatcher) {
        val treeA = TestUri("content://com.android.externalstorage.documents/tree/primary%3AMovies")
        val treeB = TestUri("content://com.android.externalstorage.documents/tree/primary%3AVideos")
        val sharedDocUri = TestUri("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3Ashared.mkv")
        val uniqueDocUri = TestUri("content://com.android.externalstorage.documents/tree/primary%3AVideos/document/primary%3Aunique.mkv")

        mockkStatic(Uri::class)
        mockkStatic(DocumentsContract::class)
        every { Uri.parse(any()) } answers { TestUri(firstArg()) }
        every { DocumentsContract.getTreeDocumentId(treeA) } returns "primary:Movies"
        every { DocumentsContract.getTreeDocumentId(treeB) } returns "primary:Videos"
        every { DocumentsContract.buildDocumentUriUsingTree(treeA, "primary:Movies") } returns TestUri("content://doc/rootA")
        every { DocumentsContract.buildDocumentUriUsingTree(treeB, "primary:Videos") } returns TestUri("content://doc/rootB")
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeA, "primary:Movies") } returns TestUri("content://children/A")
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeB, "primary:Videos") } returns TestUri("content://children/B")
        every { DocumentsContract.buildDocumentUriUsingTree(treeA, "doc_shared") } returns sharedDocUri
        every { DocumentsContract.buildDocumentUriUsingTree(treeB, "doc_shared") } returns sharedDocUri
        every { DocumentsContract.buildDocumentUriUsingTree(treeB, "doc_unique") } returns uniqueDocUri

        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.query(TestUri("content://doc/rootA"), any(), null, null, null) } returns null
        every { resolver.query(TestUri("content://doc/rootB"), any(), null, null, null) } returns null
        every { resolver.query(TestUri("content://children/A"), any(), null, null, null) } returns fakeDocCursor(
            listOf(arrayOf("doc_shared", "shared.mkv", "video/x-matroska", 1024L, 1_000_000L))
        )
        every { resolver.query(TestUri("content://children/B"), any(), null, null, null) } returns fakeDocCursor(
            listOf(
                arrayOf("doc_shared", "shared.mkv", "video/x-matroska", 1024L, 1_000_000L),
                arrayOf("doc_unique", "unique.mkv", "video/x-matroska", 2048L, 2_000_000L),
            )
        )

        val context = mockk<Context>(relaxed = true) {
            every { contentResolver } returns resolver
        }
        val fakeStore = object : DataStore<List<String>> {
            private val state = MutableStateFlow(listOf(treeA.toString(), treeB.toString()))
            override val data: Flow<List<String>> = state
            override suspend fun updateData(transform: suspend (t: List<String>) -> List<String>): List<String> {
                val updated = transform(state.value)
                state.value = updated
                return updated
            }
        }
        val repository = DefaultCustomFolderRepository(context, fakeStore, testDispatcher)

        val items = repository.scanCustomFolders()

        assertEquals(2, items.size)
        assertEquals(listOf(sharedDocUri.toString(), uniqueDocUri.toString()), items.map { it.uriString })
    }

    private fun fakeDocCursor(rows: List<Array<Any?>>): Cursor {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
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
        return cursor
    }
}
