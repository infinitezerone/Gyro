package dev.gyro.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.datastore.di.CustomFolderUris
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultCustomFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @CustomFolderUris private val dataStore: DataStore<List<String>>,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : CustomFolderRepository {

    override val savedFolderUris: Flow<Set<Uri>> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyList()) else throw cause }
        .map { stored -> stored.mapNotNull(::parseUri).toSet() }

    override suspend fun addFolder(treeUri: Uri): Boolean {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!granted) return false

        return try {
            dataStore.updateData { stored -> (stored + treeUri.toString()).distinct() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun removeFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        try {
            dataStore.updateData { stored -> stored - treeUri.toString() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }

    override suspend fun getFolderDetails(): List<CustomFolderDetail> = withContext(ioDispatcher) {
        val result = mutableListOf<CustomFolderDetail>()

        for (treeUri in savedFolderUris.first()) {
            currentCoroutineContext().ensureActive()
            try {
                val folderName = getDocDisplayName(treeUri) ?: "自定义目录"
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val items = mutableListOf<VideoMediaItem>()
                scanFolderBfs(treeUri, rootDocId, folderName, items)
                val totalSize = items.sumOf { it.sizeBytes }
                result.add(
                    CustomFolderDetail(
                        uriString = treeUri.toString(),
                        displayName = folderName,
                        videoCount = items.size,
                        totalSizeBytes = totalSize,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading folder details for $treeUri", e)
            }
        }
        result
    }

    override suspend fun scanCustomFolders(): List<VideoMediaItem> = withContext(ioDispatcher) {
        val result = mutableListOf<VideoMediaItem>()

        for (treeUri in savedFolderUris.first()) {
            currentCoroutineContext().ensureActive()
            try {
                val folderName = getDocDisplayName(treeUri) ?: "自定义目录"
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                scanFolderBfs(treeUri, rootDocId, folderName, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed scanning folder $treeUri", e)
            }
        }
        result
    }

    private fun parseUri(raw: String): Uri? = try {
        Uri.parse(raw)
    } catch (_: Exception) {
        null
    }

    private data class QueueNode(val docId: String, val folderName: String, val depth: Int)

    private suspend fun scanFolderBfs(
        treeUri: Uri,
        rootDocId: String,
        rootFolderName: String,
        outList: MutableList<VideoMediaItem>,
    ) {
        val queue = ArrayDeque<QueueNode>()
        val visitedDocIds = HashSet<String>()

        queue.add(QueueNode(rootDocId, rootFolderName, depth = 0))
        visitedDocIds.add(rootDocId)

        val maxSafeDepth = 15

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (currentDocId, currentFolderName, currentDepth) = queue.removeFirst()

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )

            try {
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val childDocId = cursor.getString(idCol) ?: continue
                        val displayName = cursor.getString(nameCol) ?: "Unknown"
                        val mimeType = cursor.getString(mimeCol) ?: ""
                        val sizeBytes = cursor.getLong(sizeCol)
                        val lastModified = cursor.getLong(dateCol)

                        if (displayName.startsWith(".")) {
                            continue
                        }

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (currentDepth < maxSafeDepth && visitedDocIds.add(childDocId)) {
                                queue.add(QueueNode(childDocId, displayName, currentDepth + 1))
                            }
                        } else if (mimeType.startsWith("video/") || isVideoFile(displayName)) {
                            val fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                            outList.add(
                                VideoMediaItem(
                                    id = childDocId.hashCode().toLong(),
                                    uriString = fileDocUri.toString(),
                                    displayName = displayName,
                                    durationMs = 0L,
                                    sizeBytes = sizeBytes,
                                    width = 0,
                                    height = 0,
                                    dateModified = lastModified / 1000L,
                                    folderName = currentFolderName,
                                    mimeType = mimeType,
                                )
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed querying children of $currentDocId", e)
            }
        }
    }

    private fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    private fun getDocDisplayName(treeUri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "DefaultCustomFolderRepository"
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "ts", "m2ts")
    }
}
