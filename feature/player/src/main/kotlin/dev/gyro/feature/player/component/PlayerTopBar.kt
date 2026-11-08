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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
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
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.component.GyroGhostIconButton

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)
 * - 全悬浮圆盘按键设计（返回、字幕、画中画、设置均为 40dp 悬浮半透明磨砂圆纽）
 * - 彻底告别压顶黑条渐变，保持画面通透纯粹
 * - 标题配合柔和文字投影，兼顾不同背景下的极致可读性
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
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回键：40dp 悬浮磨砂圆盘
            GyroCircleActionButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                size = 40.dp,
                iconSize = 20.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 视频标题与参数微标（带文字阴影，无需黑底遮罩也能清晰辨识）
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            blurRadius = 8f
                        )
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!mediaSubtitle.isNullOrBlank()) {
                    Text(
                        text = mediaSubtitle,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            fontFeatureSettings = "tnum",
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.85f),
                                blurRadius = 6f
                            )
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 字幕一键直达入口：采用国际标准 [CC] (Closed Caption) 图标
            GyroCircleActionButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                icon = Icons.Default.ClosedCaption,
                contentDescription = stringResource(R.string.sheet_tab_subtitles),
                size = 40.dp,
                iconSize = 22.dp
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 画中画 (PiP)：40dp 悬浮磨砂圆盘
            GyroCircleActionButton(
                onClick = onEnterPiP,
                icon = Icons.Default.PictureInPictureAlt,
                contentDescription = stringResource(R.string.cd_pip),
                size = 40.dp,
                iconSize = 20.dp
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 设置与全量面板入口 (⚙️ Settings)：40dp 悬浮磨砂圆盘
            GyroCircleActionButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                size = 40.dp,
                iconSize = 20.dp
            )
        }
    }
}
