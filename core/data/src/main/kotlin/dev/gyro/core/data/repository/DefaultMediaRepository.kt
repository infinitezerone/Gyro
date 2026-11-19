package dev.gyro.core.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.ApplicationScope
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.data.model.MediaDeleteResult
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
    private val customFolderRepository: CustomFolderRepository,
) : MediaRepository {

    /** `null` until the first scan lands: "unknown" must stay distinguishable from "empty". */
    private val cachedVideos = MutableStateFlow<List<VideoMediaItem>?>(null)

    /** Conflated on purpose: a bulk import fires hundreds of changes and only the last one matters. */
    private val refreshSignals = Channel<Unit>(Channel.CONFLATED)

    private val scanMutex = Mutex()

    /** MediaStore generation after the last scan, used to catch changes an observer missed. */
    private var lastKnownGeneration: Long? = null

    init {
        // MediaStore is a ContentProvider, so a change notification is the only mechanism the
        // platform offers for keeping a list in sync with it. Registered for the process lifetime:
        // data freshness must not depend on which screen happens to be subscribed.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                requestRefresh()
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                true,
                observer,
            )
        }.onFailure { Log.w(TAG, "MediaStore observer registration failed", it) }

        // Granted SAF folders are part of the library but live in DataStore, not MediaStore, so
        // the provider observer above never sees them change. Subscribe for the process lifetime
        // too: a folder added while no screen is collecting must still invalidate the cache before
        // the home screen comes back. The first value is the current set; only changes matter.
        scope.launch {
            customFolderRepository.savedFolderUris
                .distinctUntilChanged()
                .drop(1)
                .collect { requestRefresh() }
        }

        scope.launch {
            for (signal in refreshSignals) {
                // Let a burst settle, then collapse anything queued behind it into one scan.
                delay(OBSERVER_SETTLE_MS)
                while (refreshSignals.tryReceive().isSuccess) {
                    // Drain.
                }
                scan()
            }
        }
    }

    override fun observeVideos(): StateFlow<List<VideoMediaItem>?> = cachedVideos.asStateFlow()

    override fun requestRefresh() {
        refreshSignals.trySend(Unit)
    }

    override fun ensureLoaded() {
        if (cachedVideos.value == null) {
            requestRefresh()
            return
        }
        // Belt and braces for ROMs that throttle or drop observer callbacks: the generation counter
        // is cheap to read and only a real change costs a scan.
        val generation = currentGeneration() ?: return
        if (generation != lastKnownGeneration) {
            requestRefresh()
        }
    }

    private fun currentGeneration(): Long? = runCatching {
        // getGeneration(Context, String) arrived in API 30. On 29 the call throws NoSuchMethodError,
        // which runCatching absorbs: the observer and manual refresh remain the triggers there, an
        // acceptable degradation for a fallback that is not the primary invalidation path.
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
    }.getOrNull()

    private suspend fun scan() {
        scanMutex.withLock {
            val loaded = getVideos()
            cachedVideos.value = loaded
            lastKnownGeneration = currentGeneration()
        }
    }

    override suspend fun getDisplayName(uri: Uri): String? = withContext(ioDispatcher) {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0) null else cursor.getString(column)?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Missing providers, revoked grants and unsupported metadata must not prevent playback.
            null
        }
    }

    override suspend fun getVideos(): List<VideoMediaItem> = withContext(ioDispatcher) {
        val videoList = mutableListOf<VideoMediaItem>()
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
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

        val selection = "${MediaStore.Video.Media.IS_PENDING} = 0"
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val folderName = cursor.getString(bucketColumn) ?: "Videos"
                    val mimeType = cursor.getString(mimeTypeColumn)

                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )

                    videoList.add(
                        VideoMediaItem(
                            id = id,
                            uriString = contentUri.toString(),
                            displayName = name,
                            durationMs = duration,
                            sizeBytes = size,
                            width = width,
                            height = height,
                            dateModified = dateModified,
                            folderName = folderName,
                            mimeType = mimeType,
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore video query failed", e)
        }

        try {
            val customVideos = customFolderRepository.scanCustomFolders()
            val seenUris = videoList.mapTo(HashSet()) { it.uriString }
            val seenNameSizes = videoList.mapTo(HashSet()) { it.displayName to it.sizeBytes }
            for (item in customVideos) {
                val nameSize = item.displayName to item.sizeBytes
                if (item.uriString !in seenUris && nameSize !in seenNameSizes) {
                    seenUris.add(item.uriString)
                    seenNameSizes.add(nameSize)
                    videoList.add(item)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Custom folder scan failed", e)
        }

        videoList
    }

    override suspend fun deleteVideo(uri: Uri): MediaDeleteResult = withContext(ioDispatcher) {
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val deleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                if (deleted) {
                    requestRefresh()
                    MediaDeleteResult.Deleted
                } else {
                    MediaDeleteResult.Failed("DocumentsContract.deleteDocument returned false")
                }
            } else {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        try {
                            val rows = context.contentResolver.delete(uri, null, null)
                            if (rows > 0) {
                                requestRefresh()
                                MediaDeleteResult.Deleted
                            } else {
                                val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                                MediaDeleteResult.RequiresIntentSender(request.intentSender)
                            }
                        } catch (_: SecurityException) {
                            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                            MediaDeleteResult.RequiresIntentSender(request.intentSender)
                        }
                    }
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                        try {
                            val rows = context.contentResolver.delete(uri, null, null)
                            if (rows > 0) {
                                requestRefresh()
                                MediaDeleteResult.Deleted
                            } else {
                                MediaDeleteResult.Failed("Delete returned 0 rows")
                            }
                        } catch (recoverable: RecoverableSecurityException) {
                            MediaDeleteResult.RequiresIntentSender(recoverable.userAction.actionIntent.intentSender)
                        }
                    }
                    else -> {
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) {
                            requestRefresh()
                            MediaDeleteResult.Deleted
                        } else {
                            MediaDeleteResult.Failed("Delete returned 0 rows")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete video $uri", t)
            MediaDeleteResult.Failed(t.message, t)
        }
    }

    private companion object {
        const val TAG = "DefaultMediaRepository"

        /** Long enough for a bulk import burst to collapse into a single scan. */
        const val OBSERVER_SETTLE_MS = 400L
    }
}
