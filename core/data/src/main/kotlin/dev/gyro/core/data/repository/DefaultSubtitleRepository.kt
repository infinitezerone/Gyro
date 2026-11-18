package dev.gyro.core.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
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
                val baseName = videoFile.nameWithoutExtension.lowercase()
                scanLocalDirectory(videoFile.parentFile, baseName, results)
                return@withContext results.distinct()
            }

            // Case 2: SAF Tree Document URI (single-file OpenDocument URIs lack a tree and throw in getTreeDocumentId)
            if (DocumentsContract.isTreeUri(videoUri) &&
                (DocumentsContract.isDocumentUri(context, videoUri) ||
                    videoUri.authority?.contains("com.android.externalstorage.documents") == true)
            ) {
                val docId = DocumentsContract.getDocumentId(videoUri)
                val parentDocId = when {
                    docId.contains('/') -> docId.substringBeforeLast('/')
                    docId.contains(':') -> "${docId.substringBefore(':')}:"
                    else -> ""
                }
                if (parentDocId.isNotBlank()) {
                    val treeUri = DocumentsContract.buildChildDocumentsUriUsingTree(videoUri, parentDocId)
                    val rawVideoName = videoDisplayName ?: docId.substringAfterLast('/').substringAfterLast(':')
                    val videoBase = rawVideoName.substringBeforeLast('.').lowercase()
                    if (videoBase.isNotEmpty()) {
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
                                if (name != null && id != null && isMatchingSubtitle(name, videoBase)) {
                                    val subDocUri = DocumentsContract.buildDocumentUriUsingTree(videoUri, id)
                                    results.add(subDocUri)
                                    Log.i(TAG, "Found SAF companion subtitle: $name -> $subDocUri")
                                }
                            }
                        }
                    }
                }
                return@withContext results.distinct()
            }

            // Case 3: MediaStore content://media/... URI
            if (videoUri.scheme == ContentResolver.SCHEME_CONTENT &&
                videoUri.authority == MediaStore.AUTHORITY
            ) {
                findMediaStoreCompanionSubtitles(videoUri, videoDisplayName, results)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed scanning companion subtitles for uri=$videoUri: ${t.message}")
        }

        results.distinct()
    }

    private suspend fun scanLocalDirectory(
        parent: File?,
        videoBase: String,
        results: MutableList<Uri>,
    ) {
        if (videoBase.isEmpty() || parent == null || !parent.exists() || !parent.isDirectory) return
        parent.listFiles()?.forEach { file ->
            currentCoroutineContext().ensureActive()
            if (file.isFile && isMatchingSubtitle(file.name, videoBase)) {
                results.add(Uri.fromFile(file))
                Log.i(TAG, "Found local companion subtitle: ${file.absolutePath}")
            }
        }
    }

    private suspend fun findMediaStoreCompanionSubtitles(
        videoUri: Uri,
        videoDisplayName: String?,
        results: MutableList<Uri>,
    ) {
        @Suppress("DEPRECATION")
        val dataColumn = MediaStore.MediaColumns.DATA
        var relativePath: String? = null
        var queriedName: String? = null
        var dataPath: String? = null

        context.contentResolver.query(
            videoUri,
            arrayOf(
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                dataColumn,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(dataColumn)
                relativePath = if (relCol >= 0) cursor.getString(relCol)?.takeIf { it.isNotBlank() } else null
                queriedName = if (nameCol >= 0) cursor.getString(nameCol)?.takeIf { it.isNotBlank() } else null
                dataPath = if (dataCol >= 0) cursor.getString(dataCol)?.takeIf { it.isNotBlank() } else null
            }
        }

        val effectiveName = videoDisplayName ?: queriedName ?: dataPath?.let { File(it).name } ?: return
        val videoBase = effectiveName.substringBeforeLast('.').lowercase()
        if (videoBase.isEmpty()) return

        val filesCollection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        if (!relativePath.isNullOrBlank()) {
            context.contentResolver.query(
                filesCollection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (id >= 0L && name != null && isMatchingSubtitle(name, videoBase)) {
                        val subUri = ContentUris.withAppendedId(filesCollection, id)
                        results.add(subUri)
                        Log.i(TAG, "Found MediaStore companion subtitle: $name -> $subUri")
                    }
                }
            }
        }

        if (results.isEmpty() && !dataPath.isNullOrBlank()) {
            val parentPath = File(dataPath).parent
            if (!parentPath.isNullOrBlank()) {
                val normalizedParent = parentPath.trimEnd('/')
                context.contentResolver.query(
                    filesCollection,
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        dataColumn,
                    ),
                    "$dataColumn LIKE ?",
                    arrayOf("$normalizedParent/%"),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val candDataCol = cursor.getColumnIndex(dataColumn)
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                        val candData = if (candDataCol >= 0) cursor.getString(candDataCol) else null
                        val candParent = candData?.let { File(it).parent }
                        if (candParent != null && candParent != parentPath) continue
                        val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                            ?: candData?.let { File(it).name }
                        if (id >= 0L && name != null && isMatchingSubtitle(name, videoBase)) {
                            val subUri = ContentUris.withAppendedId(filesCollection, id)
                            results.add(subUri)
                            Log.i(TAG, "Found MediaStore companion subtitle via DATA: $name -> $subUri")
                        }
                    }
                }
                if (results.isEmpty()) {
                    scanLocalDirectory(File(parentPath), videoBase, results)
                }
            }
        }
    }

    private fun isMatchingSubtitle(candidateName: String, videoBase: String): Boolean {
        val dotIdx = candidateName.lastIndexOf('.')
        if (dotIdx <= 0) return false
        val ext = candidateName.substring(dotIdx + 1).lowercase()
        if (ext !in SUB_EXTENSIONS) return false
        val candBase = candidateName.substring(0, dotIdx).lowercase()
        return candBase == videoBase ||
            candBase.startsWith("$videoBase.") ||
            candBase.startsWith("${videoBase}_")
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
