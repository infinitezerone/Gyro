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
        // 1. 画面比例与解码
        val hwDesc = if (state.hwdec.contains("mediacodec")) "硬件解码 (MediaCodec)" else "软件解码"
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_aspect),
            subtitle = "${state.currentResizeMode.label} · $hwDesc",
            icon = Icons.Default.AspectRatio,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.ASPECT) }
        )

        // 2. 音轨与音效
        val currentAudioName = state.audioTracks.firstOrNull { it.isSelected }?.title
            ?: stringResource(R.string.track_none_audio)
        val nightDesc = if (state.nightMode) "夜间音效已开" else "标准立体声"
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_audio),
            subtitle = "$currentAudioName · $nightDesc",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.AUDIO) }
        )

        // 3. 字幕管理与微调
        val currentSubName = state.subtitleTracks.firstOrNull { it.isSelected }?.title ?: "已关闭"
        val delayDesc = if (Math.abs(state.subDelaySec) > 0.01) {
            "延迟 ${String.format(Locale.US, "%.1f", state.subDelaySec)}s"
        } else {
            "无延迟"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_subtitles),
            subtitle = "$currentSubName · $delayDesc",
            icon = Icons.Default.ClosedCaption,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SUBTITLES) }
        )

        // 4. 播放速度
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_speed),
            subtitle = "当前倍速: ${state.currentSpeed}x",
            icon = Icons.Default.Speed,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SPEED) }
        )

        // 5. 着色器与 Anime4K
        val shaderLabel = if (state.shaderState.activeShader != GlslShader.OFF) {
            state.shaderState.displayName ?: state.shaderState.activeShader.name
        } else {
            "原生渲染 (未开启)"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_shaders),
            subtitle = shaderLabel,
            icon = Icons.Default.AutoAwesome,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SHADERS) }
        )

        // 6. 视听画质增强
        val debandDesc = if (state.deband) "去色带已开 · 色彩校准" else "色彩与明暗调节"
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_audiovisual),
            subtitle = debandDesc,
            icon = Icons.Default.Tune,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.AUDIOVISUAL) }
        )

        // 7. A/V 同步校准
        val syncDesc = if (Math.abs(state.subDelaySec) > 0.01 || Math.abs(state.audioDelaySec) > 0.01) {
            "字幕 ${String.format(Locale.US, "%.1f", state.subDelaySec)}s · 音频 ${String.format(Locale.US, "%.1f", state.audioDelaySec)}s"
        } else {
            "音画同步微调"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_sync),
            subtitle = syncDesc,
            icon = Icons.Default.Schedule,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.SYNC) }
        )

        // 8. 实时性能与统计
        val resDesc = if (state.videoWidth > 0 && state.videoHeight > 0) {
            "${state.videoWidth}×${state.videoHeight} · ${String.format(Locale.US, "%.1f", state.fps)} FPS"
        } else {
            "实时性能与媒体详情"
        }
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_stats),
            subtitle = resDesc,
            icon = Icons.Default.Info,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.STATS) }
        )

        // 9. 媒体文件信息
        SettingsHubItem(
            title = stringResource(R.string.sheet_tab_info),
            subtitle = state.mediaInfo?.title ?: "容器、编解码与码流详情",
            icon = Icons.Default.Info,
            iconTint = SheetAccentBlue,
            onClick = { onNavigate(PlayerSheetTab.INFO) }
        )
    }
}
