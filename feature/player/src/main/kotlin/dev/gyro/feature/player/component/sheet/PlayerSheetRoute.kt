package dev.gyro.feature.player.component.sheet

import androidx.annotation.StringRes
import dev.gyro.core.designsystem.R

/**
 * 播放页抽屉的唯一路由来源。
 *
 * [titleRes] 同时作为二级页标题，[parent] 决定返回层级；新增页面只需在这里登记一次，
 * 再到 [PlayerSettingsSheetContent] 补一个内容分支，不再维护第二张标题表。
 */
sealed class PlayerSheetRoute(
    @StringRes val titleRes: Int,
    val parent: PlayerSheetRoute?
) {
    data object SETTINGS_HUB : PlayerSheetRoute(R.string.settings_title, null)
    data object PLAYLIST : PlayerSheetRoute(R.string.page_playlist, SETTINGS_HUB)
    data object CHAPTERS : PlayerSheetRoute(R.string.page_chapters, SETTINGS_HUB)
    data object ASPECT : PlayerSheetRoute(R.string.aspect_title, SETTINGS_HUB)
    data object AUDIO_TRACKS : PlayerSheetRoute(R.string.settings_audio_track, SETTINGS_HUB)
    data object AUDIO_BOOST : PlayerSheetRoute(R.string.settings_audio_boost, SETTINGS_HUB)
    data object SUBTITLE_TRACKS : PlayerSheetRoute(R.string.settings_subtitle_track, SETTINGS_HUB)
    data object TIMER : PlayerSheetRoute(R.string.sleep_timer_title, SETTINGS_HUB)
    data object PLAYBACK_MODE : PlayerSheetRoute(R.string.playback_mode_title, SETTINGS_HUB)
    data object SPEED : PlayerSheetRoute(R.string.settings_playback_speed, SETTINGS_HUB)
    data object STATS_INFO : PlayerSheetRoute(R.string.page_stats_info, SETTINGS_HUB)

    data object MORE_PLAYBACK : PlayerSheetRoute(R.string.more_playback_settings, SETTINGS_HUB)
    data object HWDEC : PlayerSheetRoute(R.string.stats_decoder, MORE_PLAYBACK)
    data object SHADERS : PlayerSheetRoute(R.string.page_shaders, MORE_PLAYBACK)
    data object COLOR : PlayerSheetRoute(R.string.settings_color_calibration, MORE_PLAYBACK)
    data object AUDIO_EFFECTS : PlayerSheetRoute(R.string.sheet_tab_audio_effects, MORE_PLAYBACK)
    data object SUBTITLE_STYLE : PlayerSheetRoute(R.string.settings_subtitle_style, MORE_PLAYBACK)
    data object AUDIO_DELAY : PlayerSheetRoute(R.string.sync_audio_delay, MORE_PLAYBACK)
    data object SUBTITLE_DELAY : PlayerSheetRoute(R.string.sync_sub_delay, MORE_PLAYBACK)
}
