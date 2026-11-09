package dev.gyro.feature.player.component.sheet.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import dev.gyro.feature.player.component.sheet.common.SheetSectionHeader
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import dev.gyro.core.designsystem.R
import dev.gyro.core.model.GlslShader
import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import dev.gyro.feature.player.component.sheet.PlayerSheetUiState
import dev.gyro.feature.player.component.sheet.common.SettingsHubItem
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import java.util.Locale

/**
 * 全局设置分类大厅 (SettingsHubTab)：每项展示当前值摘要，点击进入子页。
 */
@Composable
internal fun SettingsHubTabContent(
    state: PlayerSheetUiState,
    onNavigate: (PlayerSheetTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ========== 1. 播放控制 ==========
        SheetSectionHeader("播放控制")

        if (state.playlist.size > 1 || state.chapters.isNotEmpty()) {
            val plText = if (state.playlist.size > 1) "${state.currentPlaylistIndex + 1} / ${state.playlist.size}" else ""
            val chText = if (state.chapters.isNotEmpty()) " · ${state.chapters.size} 章节" else ""
            val combined = "$plText$chText".trim().removePrefix("· ").trim()
            SettingsHubItem(
                title = stringResource(R.string.sheet_tab_playlist),
                subtitle = combined,
                icon = Icons.Default.VideoLibrary,
                iconTint = SheetAccentBlue,
                onClick = { onNavigate(PlayerSheetTab.PLAYLIST) }
            )
        }

        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_speed),
            subtitle = "${state.currentSpeed}x",
            icon = Icons.Default.Speed,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )

        val hwDesc = if (state.hwdec.contains("mediacodec")) "硬解" else "软解"
        val hdrDesc = if (state.hdrFormat.isNotEmpty()) " · ${state.hdrFormat}" else ""
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_aspect),
            subtitle = "${state.currentResizeMode.label} · $hwDesc$hdrDesc",
            icon = Icons.Default.AspectRatio,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )

        // ========== 2. 声画配置 ==========
        SheetSectionHeader("声画配置")

        val currentAudioName = state.audioTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.track_none_audio)
        val nightDesc = if (state.nightMode) " · 夜间模式" else ""
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_audio),
            subtitle = "$currentAudioName$nightDesc",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.AUDIO) }
        )

        val currentSubName = state.subtitleTracks.firstOrNull { it.isSelected }?.title ?: "关闭"
        val delayDesc = if (Math.abs(state.subDelaySec) > 0.01) {
            " · 延迟 ${String.format(Locale.US, "%.1f", state.subDelaySec)}s"
        } else {
            ""
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_subtitles),
            subtitle = "$currentSubName$delayDesc",
            icon = Icons.Default.ClosedCaption,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLES) }
        )

        val syncDesc = if (Math.abs(state.subDelaySec) > 0.01 || Math.abs(state.audioDelaySec) > 0.01) {
            "字幕 ${String.format(Locale.US, "%.1f", state.subDelaySec)}s · 音频 ${String.format(Locale.US, "%.1f", state.audioDelaySec)}s"
        } else {
            "已同步"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_sync),
            subtitle = syncDesc,
            icon = Icons.Default.Schedule,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SYNC) }
        )

        // ========== 3. 画质增强 ==========
        SheetSectionHeader("画质增强")

        val shaderLabel = if (state.shaderState.activeShader != GlslShader.OFF) {
            state.shaderState.displayName ?: state.shaderState.activeShader.name
        } else {
            "已关闭"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_shaders),
            subtitle = shaderLabel,
            icon = Icons.Default.AutoAwesome,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SHADERS) }
        )

        val debandDesc = if (state.deband) "去色带已开" else "默认"
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_audiovisual),
            subtitle = debandDesc,
            icon = Icons.Default.Tune,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.AUDIOVISUAL) }
        )

        // ========== 4. 系统与诊断 ==========
        SheetSectionHeader("系统与诊断")

        val resDesc = if (state.videoWidth > 0 && state.videoHeight > 0) {
            "${state.videoWidth}×${state.videoHeight} · ${String.format(Locale.US, "%.1f", state.fps)} FPS"
        } else {
            "性能监控"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_stats),
            subtitle = resDesc,
            icon = Icons.Default.Info,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.STATS) }
        )

        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_info),
            subtitle = state.mediaInfo?.title ?: "文件与编码",
            icon = Icons.Default.Info,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.INFO) }
        )
    }
}
