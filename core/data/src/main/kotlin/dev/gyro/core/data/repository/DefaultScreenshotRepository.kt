package dev.gyro.core.data.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gyro.core.common.dispatcher.Dispatcher
import dev.gyro.core.common.dispatcher.GyroDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main-safe implementation of [ScreenshotRepository] persisting JPEG frames to [MediaStore]
 * (`Pictures/Gyro`) with app-scoped external Pictures directory fallback.
 */
@Singleton
class DefaultScreenshotRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GyroDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : ScreenshotRepository {

    override suspend fun saveScreenshot(
        bitmap: Bitmap,
        mediaTitle: String,
        positionMs: Long,
    ): Boolean = withContext(ioDispatcher) {
        val nowMs = System.currentTimeMillis()
        val filename = buildFilename(mediaTitle, positionMs, nowMs)
        saveToMediaStore(bitmap, filename, nowMs) || saveToAppStorage(bitmap, filename)
    }

    private fun saveToMediaStore(
        bitmap: Bitmap,
        filename: String,
        nowMs: Long,
    ): Boolean = try {
        val nowSec = nowMs / 1000L
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, nowMs)
            put(MediaStore.Images.Media.DATE_ADDED, nowSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, nowSec)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gyro")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            false
        } else {
            val written = resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
            } ?: false
            if (!written) {
                resolver.delete(uri, null, null)
                false
            } else {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "saveToMediaStore failed", t)
        false
    }

    private fun saveToAppStorage(
        bitmap: Bitmap,
        filename: String,
    ): Boolean = try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        if (dir == null) {
            false
        } else {
            val file = File(dir, filename)
            val written = FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
            }
            if (written) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null,
                )
                Log.i(TAG, "saveToAppStorage succeeded: ${file.absolutePath}")
            }
            written
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "saveToAppStorage failed", t)
        false
    }

    companion object {
        private const val TAG = "DefaultScreenshotRepo"
        private val ILLEGAL_CHAR_REGEX = Regex("""[\\/:*?"<>|\s]""")

        fun buildFilename(mediaTitle: String, posMs: Long, timestampMs: Long): String {
            val safeTitle = mediaTitle.ifBlank { "Snapshot" }
                .replace(ILLEGAL_CHAR_REGEX, "_")
                .take(30)
            val posStr = formatPosition(posMs)
            return "Gyro_${safeTitle}_${posStr}_${timestampMs}.jpg"
        }

        fun formatPosition(posMs: Long): String {
            val hours = posMs / 3_600_000L
            val minutes = (posMs % 3_600_000L) / 60_000L
            val seconds = (posMs % 60_000L) / 1_000L
            return if (hours > 0) {
                String.format(Locale.US, "%02dh%02dm%02ds", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02dm%02ds", minutes, seconds)
            }
        }
    }
}
