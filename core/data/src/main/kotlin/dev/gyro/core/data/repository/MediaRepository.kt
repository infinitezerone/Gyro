package dev.gyro.core.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    val customFolderManager = CustomFolderManager(context)

    suspend fun getVideos(): List<VideoMediaItem> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoMediaItem>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val bucketColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val folderName = if (bucketColumn >= 0) cursor.getString(bucketColumn) ?: "未知" else "视频"
                    val mimeType = if (mimeTypeColumn >= 0) cursor.getString(mimeTypeColumn) else null

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    videoList.add(
                        VideoMediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            durationMs = duration,
                            sizeBytes = size,
                            width = width,
                            height = height,
                            dateModified = dateModified,
                            folderName = folderName,
                            mimeType = mimeType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 检索用户主动授权添加的外部自定义目录视频
        try {
            val customVideos = customFolderManager.scanCustomFolders()
            // 去重（避免文件名和大小完全相同的重复项）
            val existingNames = videoList.map { it.displayName to it.sizeBytes }.toSet()
            for (item in customVideos) {
                if ((item.displayName to item.sizeBytes) !in existingNames) {
                    videoList.add(item)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        videoList
    }

    fun addCustomFolder(treeUri: Uri): Boolean {
        return customFolderManager.addFolder(treeUri)
    }

    fun removeCustomFolder(treeUri: Uri) {
        customFolderManager.removeFolder(treeUri)
    }

    fun getCustomFolders(): Set<Uri> {
        return customFolderManager.getSavedFolderUris()
    }
}
