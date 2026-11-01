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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)
 * 具备标准返回键、视频标题与元信息徽章、本地打开文件入口、画中画、字幕音轨直达、音画校准与全能设置面板入口。
 */
@Composable
fun PlayerTopBar(
    title: String,
    mediaSubtitle: String? = null,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onOpenFile: () -> Unit,
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                )
            )
            .displayCutoutPadding()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回键
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Spacer(modifier = Modifier.width(4.dp))

            // 视频标题与分辨率元信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title.ifBlank { "Gyro Player" },
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

            // 打开本地媒体文件入口
            IconButton(onClick = onOpenFile, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Open File", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 画中画 (PiP)
            IconButton(onClick = onEnterPiP, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 横屏模式下展示快捷音画校准按键
            if (isLandscape) {
                IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.SYNC) }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Sync Tuning", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // 字幕与音轨入口
            IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.SUBTITLES) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Subtitles, contentDescription = "Subtitles & Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 设置与全量面板入口
            IconButton(onClick = { onOpenSheetTab(PlayerSheetTab.STATS) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Settings & Stats", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
