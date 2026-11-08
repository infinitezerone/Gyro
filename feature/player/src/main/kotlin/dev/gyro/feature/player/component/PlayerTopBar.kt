package dev.gyro.feature.player.component

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Surface
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
            // 返回键：35dp 独立悬浮微磨砂圆钮
            GyroCircleActionButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                size = 35.dp,
                iconSize = 19.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            // 视频标题与参数微标（带文字阴影，保证纯净背景可读性）
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(
                        fontSize = 14.5.sp,
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
                            color = Color.White.copy(alpha = 0.70f),
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

            // 独立悬浮功能圆钮集群（分开独立，外径精巧 35dp，间距 6dp，既常驻画中画又毫不拥挤）：
            // 1. 字幕 (CC)
            GyroCircleActionButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) },
                icon = Icons.Default.ClosedCaption,
                contentDescription = stringResource(R.string.sheet_tab_subtitles),
                size = 35.dp,
                iconSize = 19.dp
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 2. 画中画 (PiP 常驻)
            GyroCircleActionButton(
                onClick = onEnterPiP,
                icon = Icons.Default.PictureInPictureAlt,
                contentDescription = stringResource(R.string.cd_pip),
                size = 35.dp,
                iconSize = 18.dp
            )

            Spacer(modifier = Modifier.width(6.dp))

            // 3. 全局设置 (⚙)
            GyroCircleActionButton(
                onClick = { onOpenSheetTab(PlayerSheetTab.SETTINGS_HUB) },
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                size = 35.dp,
                iconSize = 18.dp
            )
        }
    }
}
