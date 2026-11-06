package dev.gyro.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Persists the SAF tree URIs the user granted and scans them for video files.
 *
 * The persisted grant survives reboots, so this repository owns the only path that may call
 * take/releasePersistableUriPermission: doing it from anywhere else silently drops the grant for
 * every other reader.
 */
interface CustomFolderRepository {
    fun getSavedFolderUris(): Set<Uri>

    fun addFolder(treeUri: Uri): Boolean

    fun removeFolder(treeUri: Uri)

    suspend fun getFolderDetails(): List<CustomFolderDetail>

    suspend fun scanCustomFolders(): List<VideoMediaItem>
}

class DefaultCustomFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CustomFolderRepository {

    private val prefs = context.getSharedPreferences("gyro_custom_folders", Context.MODE_PRIVATE)
    private val keyCustomUris = "custom_tree_uris"

    override fun getSavedFolderUris(): Set<Uri> {
        val strings = prefs.getStringSet(keyCustomUris, emptySet()) ?: emptySet()
        return strings.mapNotNull {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }

    override fun addFolder(treeUri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)

            val current = getSavedFolderUris().map { it.toString() }.toMutableSet()
            current.add(treeUri.toString())
            prefs.edit().putStringSet(keyCustomUris, current).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun removeFolder(treeUri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(treeUri, flags)
        } catch (e: Exception) {
            // ignore
        }
        val current = getSavedFolderUris().map { it.toString() }.toMutableSet()
        current.remove(treeUri.toString())
        prefs.edit().putStringSet(keyCustomUris, current).apply()
    }

    override suspend fun getFolderDetails(): List<CustomFolderDetail> = withContext(ioDispatcher) {
        val result = mutableListOf<CustomFolderDetail>()
        val uris = getSavedFolderUris()

        for (treeUri in uris) {
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
                        totalSizeBytes = totalSize
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        result
    }

    override suspend fun scanCustomFolders(): List<VideoMediaItem> = withContext(ioDispatcher) {
        val result = mutableListOf<VideoMediaItem>()
        val uris = getSavedFolderUris()

        for (treeUri in uris) {
            try {
                val folderName = getDocDisplayName(treeUri) ?: "自定义目录"
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                scanFolderBfs(treeUri, rootDocId, folderName, result)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        result
    }

    private data class QueueNode(val docId: String, val folderName: String, val depth: Int)

    private fun scanFolderBfs(
        treeUri: Uri,
        rootDocId: String,
        rootFolderName: String,
        outList: MutableList<VideoMediaItem>
    ) {
        val queue = ArrayDeque<QueueNode>()
        val visitedDocIds = HashSet<String>()

        queue.add(QueueNode(rootDocId, rootFolderName, depth = 0))
        visitedDocIds.add(rootDocId)

        val maxSafeDepth = 15

        while (queue.isNotEmpty()) {
            val (currentDocId, currentFolderName, currentDepth) = queue.removeFirst()

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
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
                                    mimeType = mimeType
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isVideoFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "ts", "m2ts")
    }

    private fun getDocDisplayName(treeUri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
