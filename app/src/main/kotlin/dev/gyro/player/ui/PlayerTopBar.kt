package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.gyro.player.R

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)
 * 职责纯粹化：
 * - 返回首页（ArrowBack）
 * - 媒体标题与实时参数徽章
 * - 纯悬浮快捷入口：画中画、字幕与音轨面板直达、全能设置与统计面板
 * - 彻底移除多余的文件夹/本地文件选择图标（选片属于首页媒体库职责）
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
                    colors = listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)
                )
            )
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回键（返回首页媒体库）
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 视频标题与参数徽章
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!mediaSubtitle.isNullOrBlank()) {
                    Text(
                        text = mediaSubtitle,
                        style = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.65f), fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 画中画 (PiP)
            IconButton(onClick = onEnterPiP, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = stringResource(R.string.cd_pip), tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 设置与全量面板入口 (直达全功能控制面板：字幕/音轨/校准/倍速/画幅/统计)
            IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_settings), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
