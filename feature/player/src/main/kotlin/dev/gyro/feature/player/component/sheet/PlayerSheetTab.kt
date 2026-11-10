package dev.gyro.feature.player.component.sheet

import androidx.annotation.StringRes
import dev.gyro.core.designsystem.R

/**
 * 播放器抽屉看板枚举 (PlayerSheetTab)
 *
 * 采用极简、高密度设置模型：
 * 1. [SETTINGS_HUB] 主设置列表（左图标+标题，右当前状态/Switch，零冗余文案）
 * 2. 聚焦的各个专属二级设置页面（需要二级就二级，专注轻量操作）
 */
enum class PlayerSheetTab(@StringRes val titleRes: Int) {
    SETTINGS_HUB(R.string.settings_title),
    PLAYLIST(R.string.sheet_tab_playlist),
    CHAPTERS(R.string.sheet_tab_chapters),

    // 各专注二级页面
    HWDEC(R.string.stats_hwdec_title),
    SHADERS(R.string.sheet_tab_shaders),
    ASPECT(R.string.sheet_tab_aspect),
    COLOR(R.string.sheet_tab_video),
    HDR_TONE_MAPPING(R.string.settings_hdr_output),
    VIDEO_SCALE(R.string.sheet_tab_video),
    AUDIO_TRACKS(R.string.sheet_tab_audio),
    AUDIO_BOOST(R.string.sheet_tab_audio),
    AUDIO_DELAY(R.string.sync_audio_delay),
    SUBTITLE_TRACKS(R.string.sheet_tab_subtitles),
    SUBTITLE_DELAY(R.string.sync_sub_delay),
    SUBTITLE_STYLE(R.string.sheet_tab_subtitles),
    SPEED(R.string.sheet_tab_speed),
    STATS_INFO(R.string.sheet_tab_stats),

    // 兼容历史调用枚举
    VIDEO(R.string.sheet_tab_video),
    AUDIO(R.string.sheet_tab_audio),
    SUBTITLES(R.string.sheet_tab_subtitles),
    AUDIOVISUAL(R.string.sheet_tab_audiovisual),
    SYNC(R.string.sheet_tab_sync),
    STATS(R.string.sheet_tab_stats),
    INFO(R.string.sheet_tab_info)
}
