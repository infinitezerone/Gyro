package dev.gyro.core.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import dev.gyro.core.model.VideoMediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Interface contract for accessing local video media.
 *
 * Aggregates videos discovered from the system [MediaStore] and user-granted custom SAF folders.
 * All functions are main-safe.
 */
interface MediaRepository {
    /**
     * Retrieves all accessible videos sorted by modification date descending.
     */
    suspend fun getVideos(): List<VideoMediaItem>
}

class DefaultMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val customFolderRepository: CustomFolderRepository,
) : MediaRepository {

    override suspend fun getVideos(): List<VideoMediaItem> = withContext(ioDispatcher) {
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
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
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
                        id
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
            val customVideos = customFolderRepository.scanCustomFolders()
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
}
