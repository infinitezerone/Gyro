package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.TestUri
import android.net.Uri
import android.provider.MediaStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library cache lives in the repository, not in a screen's subscription. These tests pin the
 * three invariants that make "revisiting the home screen" a zero-query operation: the first load
 * scans once, repeated refresh triggers collapse behind the debounce, and a changed MediaStore
 * generation is the only thing that causes a second automatic scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun ensureLoaded_scansOnce_andSubsequentCallsDoNotRescan() = runTest(dispatcher) {
        val fixture = Fixture(generation = 10L, scope = this, dispatcher = dispatcher)
        val repo = fixture.repository()

        assertNull(repo.observeVideos().value)
        repo.ensureLoaded()
        fixture.settle()
        assertEquals(1, fixture.scanCount)

        repo.ensureLoaded()
        fixture.settle()
        // Same generation, cache already present: nothing to do.
        assertEquals(1, fixture.scanCount)
    }

    @Test
    fun requestRefresh_collapsesABurstIntoOneScan() = runTest(dispatcher) {
        val fixture = Fixture(generation = 10L, scope = this, dispatcher = dispatcher)
        val repo = fixture.repository()

        repeat(20) { repo.requestRefresh() }
        fixture.settle()
        assertEquals(1, fixture.scanCount)
    }

    @Test
    fun grantedFolderChange_rescansWithoutAnyScreenSubscribed() = runTest(dispatcher) {
        val fixture = Fixture(generation = 10L, scope = this, dispatcher = dispatcher)
        val repo = fixture.repository()

        repo.ensureLoaded()
        fixture.settle()
        assertEquals(1, fixture.scanCount)

        // A folder was granted while no home screen was collecting. The repository's own
        // subscription must invalidate the cache; SAF changes never touch MediaStore generation.
        fixture.grantedFolders.value = setOf(TestUri("content://tree/new"))
        fixture.settle()
        assertEquals(2, fixture.scanCount)
    }

    @Test
    fun ensureLoaded_rescansOnlyWhenGenerationChanged() = runTest(dispatcher) {
        val fixture = Fixture(generation = 10L, scope = this, dispatcher = dispatcher)
        val repo = fixture.repository()

        repo.ensureLoaded()
        fixture.settle()
        assertEquals(1, fixture.scanCount)

        repo.ensureLoaded()
        fixture.settle()
        assertEquals(1, fixture.scanCount)

        // An observer callback was missed; the generation check is the safety net.
        fixture.generation = 11L
        repo.ensureLoaded()
        fixture.settle()
        assertEquals(2, fixture.scanCount)
    }

    private class Fixture(
        var generation: Long,
        private val scope: TestScope,
        private val dispatcher: CoroutineDispatcher,
    ) {
        var scanCount = 0
            private set

        val grantedFolders = MutableStateFlow<Set<Uri>>(emptySet())
        private val collectionUri = TestUri("content://media/external/video/media")

        fun repository(): DefaultMediaRepository {
            mockkStatic(MediaStore::class)
            mockkStatic(MediaStore.Video.Media::class)
            every { MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) } returns collectionUri
            every { MediaStore.getGeneration(any(), any()) } answers { generation }

            val cursor = mockk<Cursor>(relaxed = true) {
                every { moveToNext() } returns false
            }
            val resolver = mockk<ContentResolver>(relaxed = true) {
                every {
                    query(collectionUri, any(), any(), any(), any())
                } answers {
                    scanCount++
                    cursor
                }
            }
            val context = mockk<Context>(relaxed = true) {
                every { contentResolver } returns resolver
            }
            val customFolderRepo = mockk<CustomFolderRepository> {
                every { savedFolderUris } returns grantedFolders
                coEvery { scanCustomFolders() } returns emptyList()
            }
            return DefaultMediaRepository(
                context = context,
                ioDispatcher = dispatcher,
                scope = scope.backgroundScope,
                customFolderRepository = customFolderRepo,
            )
        }

        /** Run the debounce delay and the scan it schedules. */
        suspend fun settle() {
            with(scope) { advanceTimeBy(500) }
        }
    }
}
