package dev.gyro.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.engine.model.ResizeMode

/**
 * 顶部导航与快捷功能栏 (PlayerTopBar)
 * 包含返回、标题、画中画，以及倍速/画幅快捷胶囊和设置入口。
 */
@Composable
fun PlayerTopBar(
    title: String,
    speed: Float,
    resizeMode: ResizeMode,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onEnterPiP: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleResizeMode: () -> Unit,
    onOpenTracksSheet: () -> Unit,
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
            .padding(horizontal = 14.dp, vertical = 6.dp)
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

            // 视频标题
            Text(
                text = title.ifBlank { "Gyro Player" },
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 竖屏时将倍速和画幅移至顶部栏，让底栏彻底清爽
            if (!isLandscape) {
                // 倍速胶囊
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCycleSpeed() }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${speed}x",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 画幅胶囊
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.16f),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCycleResizeMode() }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resizeMode.label,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))
            }

            // 画中画 (PiP)
            IconButton(onClick = onEnterPiP, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 轨道（字幕/音轨）
            IconButton(onClick = onOpenTracksSheet, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Subtitles, contentDescription = "Tracks", tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // 设置
            IconButton(onClick = onOpenTracksSheet, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
