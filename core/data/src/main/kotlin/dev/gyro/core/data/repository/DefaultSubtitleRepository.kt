package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultSubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : SubtitleRepository {

    override suspend fun findCompanionSubtitles(
        videoUri: Uri,
        videoDisplayName: String?,
    ): List<Uri> = withContext(ioDispatcher) {
        val results = mutableListOf<Uri>()

        try {
            // Case 1: File scheme or absolute file path
            if (videoUri.scheme == ContentResolver.SCHEME_FILE ||
                (videoUri.scheme == null && videoUri.path?.startsWith("/") == true)
            ) {
                val path = videoUri.path ?: return@withContext emptyList()
                val videoFile = File(path)
                val parent = videoFile.parentFile
                if (parent != null && parent.exists() && parent.isDirectory) {
                    val baseName = videoFile.nameWithoutExtension.lowercase()
                    parent.listFiles()?.forEach { file ->
                        currentCoroutineContext().ensureActive()
                        val ext = file.extension.lowercase()
                        if (ext in SUB_EXTENSIONS) {
                            val candidateBase = file.nameWithoutExtension.lowercase()
                            if (candidateBase == baseName ||
                                candidateBase.startsWith("$baseName.") ||
                                candidateBase.startsWith("${baseName}_")
                            ) {
                                results.add(Uri.fromFile(file))
                                Log.i(TAG, "Found local companion subtitle: ${file.absolutePath}")
                            }
                        }
                    }
                }
                return@withContext results
            }

            // Case 2: SAF Document URI
            if (DocumentsContract.isDocumentUri(context, videoUri) ||
                videoUri.authority?.contains("com.android.externalstorage.documents") == true
            ) {
                val docId = DocumentsContract.getDocumentId(videoUri)
                val parentDocId = docId.substringBeforeLast('/', "")
                if (parentDocId.isNotBlank()) {
                    val treeUri = DocumentsContract.buildChildDocumentsUriUsingTree(videoUri, parentDocId)
                    val videoBase = (videoDisplayName ?: docId.substringAfterLast('/'))
                        .substringBeforeLast('.')
                        .lowercase()
                    context.contentResolver.query(
                        treeUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                            val id = if (idCol >= 0) cursor.getString(idCol) else null
                            if (name != null && id != null) {
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext in SUB_EXTENSIONS) {
                                    val candBase = name.substringBeforeLast('.').lowercase()
                                    if (candBase == videoBase ||
                                        candBase.startsWith("$videoBase.") ||
                                        candBase.startsWith("${videoBase}_")
                                    ) {
                                        val subDocUri = DocumentsContract.buildDocumentUriUsingTree(videoUri, id)
                                        results.add(subDocUri)
                                        Log.i(TAG, "Found SAF companion subtitle: $name -> $subDocUri")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed scanning companion subtitles for uri=$videoUri: ${t.message}")
        }

        results
    }

    override suspend fun openSubtitle(
        subtitleUri: Uri,
        consume: suspend (String) -> Boolean,
    ): Boolean {
        if (subtitleUri.scheme != ContentResolver.SCHEME_CONTENT) {
            val target = subtitleUri.path ?: subtitleUri.toString()
            return consume(target)
        }

        val pfd = withContext(ioDispatcher) {
            try {
                context.contentResolver.openFileDescriptor(subtitleUri, "r")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Failed opening subtitle descriptor for $subtitleUri: ${t.message}")
                null
            }
        } ?: return false

        var detached = false
        return try {
            currentCoroutineContext().ensureActive()
            val fd = pfd.detachFd()
            detached = true
            if (fd < 0) return false
            val accepted = try {
                consume("fdclose://$fd")
            } catch (t: Throwable) {
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                throw t
            }
            if (!accepted) {
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            }
            accepted
        } finally {
            if (!detached) {
                runCatching { pfd.close() }
            }
        }
    }

    private companion object {
        const val TAG = "DefaultSubtitleRepo"
        val SUB_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")
    }
}
