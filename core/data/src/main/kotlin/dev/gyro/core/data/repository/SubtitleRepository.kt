package dev.gyro.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Repository interface for scanning and managing companion subtitle files.
 * Implementations must ensure all suspend operations are main-safe.
 */
interface SubtitleRepository {
    /**
     * Scans for companion subtitles (.srt, .ass, .ssa, .vtt) matching [videoUri].
     * Returns a list of paths or content URIs that can be passed to mpv `sub-add`.
     */
    suspend fun findCompanionSubtitles(videoUri: Uri, videoDisplayName: String? = null): List<String>
}

class DefaultSubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : SubtitleRepository {

    override suspend fun findCompanionSubtitles(
        videoUri: Uri,
        videoDisplayName: String?
    ): List<String> = withContext(ioDispatcher) {
        val results = mutableListOf<String>()

        try {
            // Case 1: File scheme or absolute file path
            if (videoUri.scheme == "file" || (videoUri.scheme == null && videoUri.path?.startsWith("/") == true)) {
                val path = videoUri.path ?: return@withContext emptyList()
                val videoFile = File(path)
                val parent = videoFile.parentFile
                if (parent != null && parent.exists() && parent.isDirectory) {
                    val baseName = videoFile.nameWithoutExtension.lowercase()
                    parent.listFiles()?.forEach { file ->
                        val ext = file.extension.lowercase()
                        if (ext in SUB_EXTENSIONS) {
                            val candidateBase = file.nameWithoutExtension.lowercase()
                            if (candidateBase == baseName || candidateBase.startsWith("$baseName.") || candidateBase.startsWith("${baseName}_")) {
                                results.add(file.absolutePath)
                                Log.i(TAG, "Found local companion subtitle: ${file.absolutePath}")
                            }
                        }
                    }
                }
                return@withContext results
            }

            // Case 2: SAF Document URI
            if (DocumentsContract.isDocumentUri(context, videoUri) || videoUri.authority?.contains("com.android.externalstorage.documents") == true) {
                runCatching {
                    val docId = DocumentsContract.getDocumentId(videoUri)
                    val parentDocId = docId.substringBeforeLast('/', "")
                    if (parentDocId.isNotBlank()) {
                        val treeUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                            videoUri,
                            parentDocId
                        )
                        val videoBase = (videoDisplayName ?: docId.substringAfterLast('/')).substringBeforeLast('.').lowercase()
                        context.contentResolver.query(
                            treeUri,
                            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            while (cursor.moveToNext()) {
                                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                                val id = if (idCol >= 0) cursor.getString(idCol) else null
                                if (name != null && id != null) {
                                    val ext = name.substringAfterLast('.', "").lowercase()
                                    if (ext in SUB_EXTENSIONS) {
                                        val candBase = name.substringBeforeLast('.').lowercase()
                                        if (candBase == videoBase || candBase.startsWith("$videoBase.") || candBase.startsWith("${videoBase}_")) {
                                            val subDocUri = DocumentsContract.buildDocumentUriUsingTree(videoUri, id)
                                            val pfd = context.contentResolver.openFileDescriptor(subDocUri, "r")
                                            val fd = pfd?.detachFd() ?: -1
                                            if (fd >= 0) {
                                                results.add("fdclose://$fd")
                                                Log.i(TAG, "Found SAF companion subtitle: $name -> fdclose://$fd")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed scanning companion subtitles for uri=$videoUri: ${t.message}")
        }

        results
    }

    private companion object {
        const val TAG = "DefaultSubtitleRepository"
        val SUB_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    }
}
