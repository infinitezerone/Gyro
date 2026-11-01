package dev.gyro.player.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Scans for companion subtitles (.srt, .ass, .ssa, .vtt) in the same directory as the video.
 */
object CompanionSubtitleScanner {

    private const val TAG = "CompanionSubtitleScanner"
    private val SUB_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

    /**
     * Find companion subtitles for a video URI.
     * Returns a list of paths or content URIs that can be passed to mpv `sub-add`.
     */
    fun findCompanionSubtitles(context: Context, videoUri: Uri, videoDisplayName: String? = null): List<String> {
        val results = mutableListOf<String>()

        try {
            // Case 1: File scheme or absolute file path
            if (videoUri.scheme == "file" || (videoUri.scheme == null && videoUri.path?.startsWith("/") == true)) {
                val path = videoUri.path ?: return emptyList()
                val videoFile = File(path)
                val parent = videoFile.parentFile
                if (parent != null && parent.exists() && parent.isDirectory) {
                    val baseName = videoFile.nameWithoutExtension.lowercase()
                    parent.listFiles()?.forEach { file ->
                        val ext = file.extension.lowercase()
                        if (ext in SUB_EXTENSIONS) {
                            val candidateBase = file.nameWithoutExtension.lowercase()
                            // Matches exact base name or baseName + language suffix (e.g. movie.zh.srt)
                            if (candidateBase == baseName || candidateBase.startsWith("$baseName.") || candidateBase.startsWith("${baseName}_")) {
                                results.add(file.absolutePath)
                                Log.i(TAG, "Found local companion subtitle: ${file.absolutePath}")
                            }
                        }
                    }
                }
                return results
            }

            // Case 2: SAF Document URI (e.g. from CustomFolderManager)
            if (DocumentsContract.isDocumentUri(context, videoUri) || videoUri.authority?.contains("com.android.externalstorage.documents") == true) {
                // If it's a tree document, query sibling documents under the same parent
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
                                            // Convert to file descriptor path for mpv
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

        return results
    }
}
