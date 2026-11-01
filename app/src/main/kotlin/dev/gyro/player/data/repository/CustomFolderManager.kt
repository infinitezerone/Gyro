package dev.gyro.player.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.gyro.player.data.model.VideoMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户自定义目录扫描与持久化管理
 */
class CustomFolderManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("gyro_custom_folders", Context.MODE_PRIVATE)
    private val keyCustomUris = "custom_tree_uris"

    fun getSavedFolderUris(): Set<Uri> {
        val strings = prefs.getStringSet(keyCustomUris, emptySet()) ?: emptySet()
        return strings.mapNotNull {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }.toSet()
    }

    fun addFolder(treeUri: Uri): Boolean {
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

    fun removeFolder(treeUri: Uri) {
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

    suspend fun scanCustomFolders(): List<VideoMediaItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<VideoMediaItem>()
        val uris = getSavedFolderUris()

        for (treeUri in uris) {
            try {
                scanTreeRecursive(treeUri, result, depth = 0, maxDepth = 3)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        result
    }

    private fun scanTreeRecursive(
        treeUri: Uri,
        outList: MutableList<VideoMediaItem>,
        depth: Int,
        maxDepth: Int,
        parentDocId: String? = null
    ) {
        if (depth > maxDepth) return

        val rootDocId = parentDocId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val folderName = rootDocId.substringAfterLast(":").substringAfterLast("/").ifBlank { "自定义目录" }

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val displayName = cursor.getString(nameCol) ?: "Unknown"
                val mimeType = cursor.getString(mimeCol) ?: ""
                val size = cursor.getLong(sizeCol)
                val lastModified = cursor.getLong(modCol)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanTreeRecursive(treeUri, outList, depth + 1, maxDepth, docId)
                } else if (isVideoFile(displayName, mimeType)) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val pseudoId = docId.hashCode().toLong()

                    outList.add(
                        VideoMediaItem(
                            id = pseudoId,
                            uri = fileUri,
                            displayName = displayName,
                            durationMs = 0L, // Custom SAF docs don't have instant duration without retriever
                            sizeBytes = size,
                            width = 0,
                            height = 0,
                            dateModified = lastModified / 1000L,
                            folderName = folderName,
                            mimeType = mimeType
                        )
                    )
                }
            }
        }
    }

    private fun isVideoFile(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
               lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".flv") ||
               lower.endsWith(".ts") || lower.endsWith(".m4v") || lower.endsWith(".wmv")
    }
}
