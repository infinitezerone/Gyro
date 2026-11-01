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
                scanTreeIterative(treeUri, result, maxDepth = 15)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        result
    }

    /**
     * 基于 BFS 广度优先迭代队列的目录扫描：
     * 1. 彻底解除 3 级限制，支持影视/动漫常见的多层级深层目录 (放宽至 15 级安全上限)；
     * 2. 具备已访问 docId 环路检测，从根本上防止软链接死循环；
     * 3. 自动跳过 . 开头的隐藏目录与系统缓存目录，兼顾深层遍历与高性能；
     * 4. 采用队列迭代而非深度递归，杜绝深层调用栈溢出 (StackOverflow)。
     */
    private fun scanTreeIterative(
        treeUri: Uri,
        outList: MutableList<VideoMediaItem>,
        maxDepth: Int = 15
    ) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val initialFolderName = rootDocId.substringAfterLast(":").substringAfterLast("/").ifBlank { "自定义目录" }

        data class DirQueueItem(val docId: String, val folderName: String, val depth: Int)

        val queue = ArrayDeque<DirQueueItem>()
        val visitedDocIds = mutableSetOf<String>()

        queue.add(DirQueueItem(rootDocId, initialFolderName, depth = 0))
        visitedDocIds.add(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty()) {
            val (currentDocId, currentFolderName, currentDepth) = queue.removeFirst()
            if (currentDepth > maxDepth) continue

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)

            try {
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

                        // 忽略隐藏文件夹与隐藏文件（如 .git, .cache, .thumbnails 等）
                        if (displayName.startsWith(".")) continue

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (currentDepth < maxDepth && visitedDocIds.add(docId)) {
                                queue.add(DirQueueItem(docId, displayName, currentDepth + 1))
                            }
                        } else if (isVideoFile(displayName, mimeType)) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val pseudoId = docId.hashCode().toLong()

                            outList.add(
                                VideoMediaItem(
                                    id = pseudoId,
                                    uri = fileUri,
                                    displayName = displayName,
                                    durationMs = 0L,
                                    sizeBytes = size,
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
                // 忽略单个无权子目录报错，不阻断其余扫描
                e.printStackTrace()
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
