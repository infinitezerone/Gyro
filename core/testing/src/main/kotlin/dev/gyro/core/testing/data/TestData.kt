package dev.gyro.core.testing.data

import android.net.TestUri
import dev.gyro.core.model.ChapterInfo
import dev.gyro.core.model.CustomFolderDetail
import dev.gyro.core.model.MediaInfo
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoMediaItem

object TestData {

    fun testVideoItem(
        id: Long = 1L,
        uriString: String = "content://media/external/video/media/$id",
        displayName: String = "Big Buck Bunny.mp4",
        durationMs: Long = 60_000L,
        sizeBytes: Long = 100_000_000L,
        folderName: String = "Movies"
    ): VideoMediaItem = VideoMediaItem(
        id = id,
        uri = TestUri(uriString),
        displayName = displayName,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        width = 1920,
        height = 1080,
        dateModified = 1700000000L,
        folderName = folderName,
        mimeType = "video/mp4"
    )

    fun testPlaybackProgress(
        uriString: String = "content://media/external/video/media/1",
        positionMs: Long = 30_000L,
        durationMs: Long = 60_000L,
        lastPlayedTimestamp: Long = 1700000000L
    ): PlaybackProgress = PlaybackProgress(
        uriString = uriString,
        positionMs = positionMs,
        durationMs = durationMs,
        lastPlayedTimestamp = lastPlayedTimestamp
    )

    fun testUserSettings(
        autoResume: Boolean = true,
        backgroundPlayMode: String = "pip",
        seekStepSeconds: Int = 10,
        defaultHwdec: String = "mediacodec",
        subAutoLoad: Boolean = true,
        gesturesEnabled: Boolean = true,
        gestureConfig: PlayerGestureConfig = PlayerGestureConfig()
    ): UserSettings = UserSettings(
        autoResume = autoResume,
        backgroundPlayMode = backgroundPlayMode,
        seekStepSeconds = seekStepSeconds,
        defaultHwdec = defaultHwdec,
        subAutoLoad = subAutoLoad,
        gesturesEnabled = gesturesEnabled,
        gestureConfig = gestureConfig
    )

    fun testPlayerGestureConfig(
        isLocked: Boolean = false,
        isVolumeEnabled: Boolean = true,
        isBrightnessEnabled: Boolean = true,
        isSeekEnabled: Boolean = true,
        isZoomEnabled: Boolean = true,
        isDoubleTapSeekEnabled: Boolean = true,
        isDoubleTapPlayPauseEnabled: Boolean = true,
        isLongPressSpeedEnabled: Boolean = true,
        swapVolumeAndBrightness: Boolean = false,
        doubleTapSeekSeconds: Int = 10,
        longPressSpeed: Float = 2.0f
    ): PlayerGestureConfig = PlayerGestureConfig(
        isLocked = isLocked,
        isVolumeEnabled = isVolumeEnabled,
        isBrightnessEnabled = isBrightnessEnabled,
        isSeekEnabled = isSeekEnabled,
        isZoomEnabled = isZoomEnabled,
        isDoubleTapSeekEnabled = isDoubleTapSeekEnabled,
        isDoubleTapPlayPauseEnabled = isDoubleTapPlayPauseEnabled,
        isLongPressSpeedEnabled = isLongPressSpeedEnabled,
        swapVolumeAndBrightness = swapVolumeAndBrightness,
        doubleTapSeekSeconds = doubleTapSeekSeconds,
        longPressSpeed = longPressSpeed
    )

    fun testChapterInfo(
        index: Int = 0,
        title: String = "Chapter 1",
        timeMs: Long = 60_000L
    ): ChapterInfo = ChapterInfo(
        index = index,
        title = title,
        timeMs = timeMs
    )

    fun testMediaInfo(
        title: String = "Test Video",
        pathOrUri: String = "content://media/1",
        containerFormat: String = "matroska",
        fileSize: Long = 1_073_741_824L,
        durationMs: Long = 120_000L,
        videoCodec: String = "hevc",
        videoResolution: String = "1920 × 1080",
        videoFps: Double = 60.0,
        videoBitrate: Long = 5_000_000L,
        audioCodec: String = "aac",
        audioChannels: String = "2.0",
        audioSampleRate: Long = 48_000L,
        audioBitrate: Long = 320_000L,
        hwdecCurrent: String = "mediacodec",
        droppedFrames: Long = 0L
    ): MediaInfo = MediaInfo(
        title = title,
        pathOrUri = pathOrUri,
        containerFormat = containerFormat,
        fileSize = fileSize,
        durationMs = durationMs,
        videoCodec = videoCodec,
        videoResolution = videoResolution,
        videoFps = videoFps,
        videoBitrate = videoBitrate,
        audioCodec = audioCodec,
        audioChannels = audioChannels,
        audioSampleRate = audioSampleRate,
        audioBitrate = audioBitrate,
        hwdecCurrent = hwdecCurrent,
        droppedFrames = droppedFrames
    )

    fun testFolderDetail(
        uriString: String = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
        displayName: String = "Movies",
        videoCount: Int = 12,
        totalSizeBytes: Long = 10_000_000_000L
    ): CustomFolderDetail = CustomFolderDetail(
        uri = TestUri(uriString),
        displayName = displayName,
        videoCount = videoCount,
        totalSizeBytes = totalSizeBytes
    )
}
