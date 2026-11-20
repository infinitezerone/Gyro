package dev.gyro.core.designsystem.icon

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.gyro.core.designsystem.R

/**
 * 全局唯一图标集，取自 Material Symbols Rounded。
 *
 * 所有图标均以标准 Android VectorDrawable XML（`res/drawable/ic_*.xml`）形式存放：
 * - 字形轴统一为 `style=rounded`、`fill=1`（实心）、`wght=500`（厚实）、`grade=0`；
 *   开关关闭态提供 `fill=0` 轮廓变体（如 [SubtitlesOutline]）。
 * - 统一采用 `24dp` 画布与 `960×960` viewBox，`fillColor="#FFFFFFFF"`，不依赖 `?attr/`
 *   等平台主题属性，既支持 Compose `Icon(tint = ...)` 着色与 IDE 实时预览，也便于后续直接迁入
 *   KMP / Compose Multiplatform `composeResources`。
 * - 方向性图标在 XML 上声明了 `android:autoMirrored="true"`，在 RTL 语言下自动水平镜像。
 * - 系统级 API（如画中画 `RemoteAction`）可直接引用 [GyroIcons.Res] 中的 `@DrawableRes`。
 */
object GyroIcons {

    /**
     * 供系统级 API（如 PiP `RemoteAction`、通知或快捷方式）使用的 `@DrawableRes` 索引。
     */
    object Res {
        // region 播放控制
        /** Material Symbols `play_arrow` · fill=1 · wght=500 */
        @DrawableRes val Play: Int = R.drawable.ic_play

        /** Material Symbols `pause` · fill=1 · wght=500 */
        @DrawableRes val Pause: Int = R.drawable.ic_pause

        /** Material Symbols `replay` · fill=1 · wght=500 */
        @DrawableRes val Replay: Int = R.drawable.ic_replay

        /** Material Symbols `skip_previous` · fill=1 · wght=500 */
        @DrawableRes val SkipPrevious: Int = R.drawable.ic_skip_previous

        /** Material Symbols `skip_next` · fill=1 · wght=500 */
        @DrawableRes val SkipNext: Int = R.drawable.ic_skip_next

        /** Material Symbols `fast_rewind` · fill=1 · wght=500 */
        @DrawableRes val FastRewind: Int = R.drawable.ic_fast_rewind

        /** Material Symbols `fast_forward` · fill=1 · wght=500 */
        @DrawableRes val FastForward: Int = R.drawable.ic_fast_forward

        /** Material Symbols `repeat` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val Repeat: Int = R.drawable.ic_repeat

        /** Material Symbols `repeat_one` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val RepeatOne: Int = R.drawable.ic_repeat_one
        // endregion

        // region 播放器悬浮层
        /** Material Symbols `fullscreen` · fill=1 · wght=500 */
        @DrawableRes val FullscreenEnter: Int = R.drawable.ic_fullscreen_enter

        /** Material Symbols `fullscreen_exit` · fill=1 · wght=500 */
        @DrawableRes val FullscreenExit: Int = R.drawable.ic_fullscreen_exit

        /** Material Symbols `lock` · fill=1 · wght=500 */
        @DrawableRes val Lock: Int = R.drawable.ic_lock

        /** Material Symbols `lock_open` · fill=1 · wght=500 */
        @DrawableRes val LockOpen: Int = R.drawable.ic_lock_open

        /** Material Symbols `photo_camera` · fill=1 · wght=500 */
        @DrawableRes val Screenshot: Int = R.drawable.ic_screenshot

        /** Material Symbols `pip` · fill=1 · wght=500 */
        @DrawableRes val Pip: Int = R.drawable.ic_pip
        // endregion

        // region 手势反馈与定时/同步
        /** Material Symbols `light_mode` · fill=1 · wght=500 */
        @DrawableRes val Brightness: Int = R.drawable.ic_brightness

        /** Material Symbols `volume_up` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val VolumeHigh: Int = R.drawable.ic_volume_high

        /** Material Symbols `volume_down` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val VolumeLow: Int = R.drawable.ic_volume_low

        /** Material Symbols `volume_off` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val VolumeMute: Int = R.drawable.ic_volume_mute

        /** Material Symbols `bedtime` · fill=1 · wght=500 */
        @DrawableRes val SleepTimer: Int = R.drawable.ic_sleep_timer

        /** Material Symbols `sync` · fill=1 · wght=500 */
        @DrawableRes val Sync: Int = R.drawable.ic_sync
        // endregion

        // region 导航与通用动作
        /** Material Symbols `arrow_back_ios_new` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val Back: Int = R.drawable.ic_back

        /** Material Symbols `more_horiz` · fill=1 · wght=500 */
        @DrawableRes val More: Int = R.drawable.ic_more

        /** Material Symbols `more_vert` · fill=1 · wght=500 */
        @DrawableRes val MoreVert: Int = R.drawable.ic_more_vert

        /** Material Symbols `arrow_forward_ios` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val ChevronRight: Int = R.drawable.ic_chevron_right

        /** Material Symbols `close` · fill=1 · wght=500 */
        @DrawableRes val Close: Int = R.drawable.ic_close

        /** Material Symbols `add` · fill=1 · wght=500 */
        @DrawableRes val Add: Int = R.drawable.ic_add

        /** Material Symbols `check` · fill=1 · wght=500 */
        @DrawableRes val Check: Int = R.drawable.ic_check

        /** Material Symbols `search` · fill=1 · wght=500 */
        @DrawableRes val Search: Int = R.drawable.ic_search

        /** Material Symbols `sort` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val Sort: Int = R.drawable.ic_sort

        /** Material Symbols `refresh` · fill=1 · wght=500 */
        @DrawableRes val Refresh: Int = R.drawable.ic_refresh

        /** Material Symbols `restart_alt` · fill=1 · wght=500 */
        @DrawableRes val RestartAlt: Int = R.drawable.ic_restart_alt

        /** Material Symbols `help` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val Help: Int = R.drawable.ic_help

        /** Material Symbols `error` · fill=1 · wght=500 */
        @DrawableRes val Error: Int = R.drawable.ic_error

        /** Material Symbols `settings` · fill=1 · wght=500 */
        @DrawableRes val Settings: Int = R.drawable.ic_settings
        // endregion

        // region 文件与编辑器
        /** Material Symbols `folder` · fill=1 · wght=500 */
        @DrawableRes val Folder: Int = R.drawable.ic_folder

        /** Material Symbols `folder_open` · fill=1 · wght=500 */
        @DrawableRes val FolderOpen: Int = R.drawable.ic_folder_open

        /** Material Symbols `folder_off` · fill=1 · wght=500 */
        @DrawableRes val FolderOff: Int = R.drawable.ic_folder_off

        /** Material Symbols `create_new_folder` · fill=1 · wght=500 */
        @DrawableRes val CreateNewFolder: Int = R.drawable.ic_create_new_folder

        /** Material Symbols `delete` · fill=1 · wght=500 */
        @DrawableRes val Delete: Int = R.drawable.ic_delete

        /** Material Symbols `save` · fill=1 · wght=500 */
        @DrawableRes val Save: Int = R.drawable.ic_save

        /** Material Symbols `content_copy` · fill=1 · wght=500 */
        @DrawableRes val ContentCopy: Int = R.drawable.ic_content_copy
        // endregion

        // region 音视频参数
        /** Material Symbols `closed_caption` · fill=1 · wght=500（字幕开启态） */
        @DrawableRes val Subtitles: Int = R.drawable.ic_subtitles

        /** Material Symbols `closed_caption` · fill=0 · wght=500（开关关闭态） */
        @DrawableRes val SubtitlesOutline: Int = R.drawable.ic_subtitles_outline

        /** Material Symbols `playlist_play` · fill=1 · wght=500 · autoMirrored */
        @DrawableRes val PlaylistPlay: Int = R.drawable.ic_playlist_play

        /** Material Symbols `headphones` · fill=1 · wght=500 */
        @DrawableRes val Headphones: Int = R.drawable.ic_headphones

        /** Material Symbols `music_note` · fill=1 · wght=500 */
        @DrawableRes val AudioTrack: Int = R.drawable.ic_audio_track

        /** Material Symbols `surround_sound` · fill=1 · wght=500 */
        @DrawableRes val SurroundSound: Int = R.drawable.ic_surround_sound

        /** Material Symbols `graphic_eq` · fill=1 · wght=500 */
        @DrawableRes val GraphicEq: Int = R.drawable.ic_graphic_eq

        /** Material Symbols `tune` · fill=1 · wght=500 */
        @DrawableRes val Tune: Int = R.drawable.ic_tune

        /** Material Symbols `speed` · fill=1 · wght=500 */
        @DrawableRes val Speed: Int = R.drawable.ic_speed

        /** Material Symbols `memory` · fill=1 · wght=500 */
        @DrawableRes val Memory: Int = R.drawable.ic_memory

        /** Material Symbols `auto_awesome` · fill=1 · wght=500 */
        @DrawableRes val AutoAwesome: Int = R.drawable.ic_auto_awesome

        /** Material Symbols `deblur` · fill=1 · wght=500 */
        @DrawableRes val Deband: Int = R.drawable.ic_deband

        /** Material Symbols `tonality` · fill=1 · wght=500 */
        @DrawableRes val ColorTone: Int = R.drawable.ic_color_tone

        /** Material Symbols `info` · fill=1 · wght=500 */
        @DrawableRes val Info: Int = R.drawable.ic_info

        /** Material Symbols `monitoring` · fill=1 · wght=500 */
        @DrawableRes val Monitoring: Int = R.drawable.ic_monitoring

        /** Material Symbols `video_library` · fill=1 · wght=500 */
        @DrawableRes val VideoLibrary: Int = R.drawable.ic_video_library

        /** Material Symbols `movie` · fill=1 · wght=500 */
        @DrawableRes val Movie: Int = R.drawable.ic_movie

        /** Material Symbols `fit_screen` · fill=1 · wght=500 */
        @DrawableRes val FitScreen: Int = R.drawable.ic_fit_screen
        // endregion
    }

    // region 播放控制

    val Play: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Play)

    val Pause: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Pause)

    val Replay: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Replay)

    val SkipPrevious: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.SkipPrevious)

    val SkipNext: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.SkipNext)

    val FastRewind: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FastRewind)

    val FastForward: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FastForward)

    val Repeat: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Repeat)

    val RepeatOne: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.RepeatOne)

    // endregion

    // region 播放器悬浮层

    val FullscreenEnter: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FullscreenEnter)

    val FullscreenExit: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FullscreenExit)

    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Lock)

    val LockOpen: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.LockOpen)

    val Screenshot: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Screenshot)

    val Pip: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Pip)

    // endregion

    // region 手势反馈与定时/同步

    val Brightness: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Brightness)

    val VolumeHigh: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.VolumeHigh)

    val VolumeLow: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.VolumeLow)

    val VolumeMute: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.VolumeMute)

    val SleepTimer: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.SleepTimer)

    val Sync: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Sync)

    // endregion

    // region 导航与通用动作

    val Back: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Back)

    val More: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.More)

    val MoreVert: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.MoreVert)

    val ChevronRight: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.ChevronRight)

    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Close)

    val Add: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Add)

    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Check)

    val Search: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Search)

    val Sort: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Sort)

    val Refresh: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Refresh)

    val RestartAlt: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.RestartAlt)

    val Help: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Help)

    val Error: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Error)

    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Settings)

    // endregion

    // region 文件与编辑器

    val Folder: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Folder)

    val FolderOpen: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FolderOpen)

    val FolderOff: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FolderOff)

    val CreateNewFolder: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.CreateNewFolder)

    val Delete: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Delete)

    val Save: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Save)

    val ContentCopy: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.ContentCopy)

    // endregion

    // region 音视频参数

    val Subtitles: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Subtitles)

    val SubtitlesOutline: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.SubtitlesOutline)

    val PlaylistPlay: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.PlaylistPlay)

    val Headphones: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Headphones)

    val AudioTrack: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.AudioTrack)

    val SurroundSound: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.SurroundSound)

    val GraphicEq: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.GraphicEq)

    val Tune: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Tune)

    val Speed: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Speed)

    val Memory: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Memory)

    val AutoAwesome: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.AutoAwesome)

    val Deband: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Deband)

    val ColorTone: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.ColorTone)

    val Info: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Info)

    val Monitoring: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Monitoring)

    val VideoLibrary: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.VideoLibrary)

    val Movie: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.Movie)

    val FitScreen: ImageVector
        @Composable get() = ImageVector.vectorResource(Res.FitScreen)

    // endregion

    internal val allDrawableNames: List<String> = listOf(
        "ic_play",
        "ic_pause",
        "ic_replay",
        "ic_skip_previous",
        "ic_skip_next",
        "ic_fast_rewind",
        "ic_fast_forward",
        "ic_repeat",
        "ic_repeat_one",
        "ic_fullscreen_enter",
        "ic_fullscreen_exit",
        "ic_lock",
        "ic_lock_open",
        "ic_screenshot",
        "ic_pip",
        "ic_brightness",
        "ic_volume_high",
        "ic_volume_low",
        "ic_volume_mute",
        "ic_sleep_timer",
        "ic_sync",
        "ic_back",
        "ic_more",
        "ic_more_vert",
        "ic_chevron_right",
        "ic_close",
        "ic_add",
        "ic_check",
        "ic_search",
        "ic_sort",
        "ic_refresh",
        "ic_restart_alt",
        "ic_help",
        "ic_error",
        "ic_settings",
        "ic_folder",
        "ic_folder_open",
        "ic_folder_off",
        "ic_create_new_folder",
        "ic_delete",
        "ic_save",
        "ic_content_copy",
        "ic_subtitles",
        "ic_subtitles_outline",
        "ic_playlist_play",
        "ic_headphones",
        "ic_audio_track",
        "ic_surround_sound",
        "ic_graphic_eq",
        "ic_tune",
        "ic_speed",
        "ic_memory",
        "ic_auto_awesome",
        "ic_deband",
        "ic_color_tone",
        "ic_info",
        "ic_monitoring",
        "ic_video_library",
        "ic_movie",
        "ic_fit_screen",
    )

    internal val allDrawableIds: List<Int> = listOf(
        Res.Play,
        Res.Pause,
        Res.Replay,
        Res.SkipPrevious,
        Res.SkipNext,
        Res.FastRewind,
        Res.FastForward,
        Res.Repeat,
        Res.RepeatOne,
        Res.FullscreenEnter,
        Res.FullscreenExit,
        Res.Lock,
        Res.LockOpen,
        Res.Screenshot,
        Res.Pip,
        Res.Brightness,
        Res.VolumeHigh,
        Res.VolumeLow,
        Res.VolumeMute,
        Res.SleepTimer,
        Res.Sync,
        Res.Back,
        Res.More,
        Res.MoreVert,
        Res.ChevronRight,
        Res.Close,
        Res.Add,
        Res.Check,
        Res.Search,
        Res.Sort,
        Res.Refresh,
        Res.RestartAlt,
        Res.Help,
        Res.Error,
        Res.Settings,
        Res.Folder,
        Res.FolderOpen,
        Res.FolderOff,
        Res.CreateNewFolder,
        Res.Delete,
        Res.Save,
        Res.ContentCopy,
        Res.Subtitles,
        Res.SubtitlesOutline,
        Res.PlaylistPlay,
        Res.Headphones,
        Res.AudioTrack,
        Res.SurroundSound,
        Res.GraphicEq,
        Res.Tune,
        Res.Speed,
        Res.Memory,
        Res.AutoAwesome,
        Res.Deband,
        Res.ColorTone,
        Res.Info,
        Res.Monitoring,
        Res.VideoLibrary,
        Res.Movie,
        Res.FitScreen,
    )
}
