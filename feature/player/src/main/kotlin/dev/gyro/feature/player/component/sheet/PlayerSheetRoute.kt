package dev.gyro.feature.player.component.sheet

import androidx.annotation.StringRes
import dev.gyro.core.designsystem.R
import dev.gyro.core.domain.session.PlaylistRepeatMode

/**
 * 播放页抽屉的唯一路由来源，最多两层（主菜单 + 单一领域子页）。
 *
 * [titleRes] 同时作为二级页标题，[parent] 决定返回层级，[key] 用于调试与状态标识。
 */
sealed class PlayerSheetRoute(
    @StringRes val titleRes: Int,
    val parent: PlayerSheetRoute?,
    val key: String,
) {
    data object SETTINGS_HUB : PlayerSheetRoute(R.string.settings_title, null, "settings_hub")
    data object VIDEO : PlayerSheetRoute(R.string.hub_group_video, SETTINGS_HUB, "video")
    data object AUDIO : PlayerSheetRoute(R.string.hub_group_audio, SETTINGS_HUB, "audio")
    data object SUBTITLES : PlayerSheetRoute(R.string.hub_group_subtitles, SETTINGS_HUB, "subtitles")
    data object SPEED : PlayerSheetRoute(R.string.settings_playback_speed, SETTINGS_HUB, "speed")
    data object STATS_INFO : PlayerSheetRoute(R.string.page_stats_info, SETTINGS_HUB, "stats_info")
    data object PLAYLIST : PlayerSheetRoute(R.string.page_playlist, SETTINGS_HUB, "playlist")
    data object CHAPTERS : PlayerSheetRoute(R.string.page_chapters, SETTINGS_HUB, "chapters")
}

/**
 * 循环切换列表播放模式：顺序播放 -> 列表循环 -> 单片循环 -> 顺序播放。
 */
internal fun nextPlaylistRepeatMode(current: PlaylistRepeatMode): PlaylistRepeatMode = when (current) {
    PlaylistRepeatMode.SEQUENTIAL -> PlaylistRepeatMode.LOOP_ALL
    PlaylistRepeatMode.LOOP_ALL -> PlaylistRepeatMode.LOOP_ONE
    PlaylistRepeatMode.LOOP_ONE -> PlaylistRepeatMode.SEQUENTIAL
}

/**
 * 根据返回栈深度严格判断是前进转场还是返回转场，避免仅按目标路由硬编码导致二级返回动画反向。
 */
internal fun isBackTransition(previousDepth: Int, targetDepth: Int): Boolean =
    targetDepth < previousDepth
