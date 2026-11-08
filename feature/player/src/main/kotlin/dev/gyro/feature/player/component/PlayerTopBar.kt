package dev.gyro.feature.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroGhostIconButton

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)
 * - 返回（ArrowBack）
 * - 媒体标题与实时参数微标
 * - 一键直达字幕选择（💬 Subtitles 快捷入口）
 * - 画中画 (PiP) 与 全能设置 (⚙️ Settings)
 * - 5 段平滑渐变暗角过渡，保护状态栏与文字可读性，消灭生硬黑切边
 */
@Composable
fun PlayerTopBar(
    title: String,
    mediaSubtitle: String? = null,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.75f),
                        Color.Black.copy(alpha = 0.50f),
                        Color.Black.copy(alpha = 0.20f),
                        Color.Black.copy(alpha = 0.05f),
                        Color.Transparent
                    )
                )
            )
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回键
            GyroGhostIconButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                iconSize = 22.dp
            )

            Spacer(modifier = Modifier.width(4.dp))

            // 视频标题与参数徽章
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!mediaSubtitle.isNullOrBlank()) {
                    Text(
                        text = mediaSubtitle,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            fontFeatureSettings = "tnum"
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 字幕一键直达入口 (💬 Subtitles)
            GyroGhostIconButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                icon = Icons.Default.Subtitles,
                contentDescription = stringResource(R.string.sheet_tab_subtitles),
                iconSize = 20.dp
            )

            // 画中画 (PiP)
            GyroGhostIconButton(
                onClick = onEnterPiP,
                icon = Icons.Default.PictureInPictureAlt,
                contentDescription = stringResource(R.string.cd_pip),
                iconSize = 20.dp
            )

            // 设置与全量面板入口 (⚙️ Settings 齿轮图标)
            GyroGhostIconButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                iconSize = 20.dp
            )
        }
    }
}
