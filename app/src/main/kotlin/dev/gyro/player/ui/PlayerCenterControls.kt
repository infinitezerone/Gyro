package dev.gyro.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 画面中央核心控制组件 (PlayerCenterControls)
 * 对齐 MX Player / Next Player 标杆心智：
 * 1. 控制栏显现时：画面正中央呈现大尺寸播放/暂停、快退 10s、快进 10s；
 * 2. 屏幕锁定时：正中仅呈现醒目的防误触解锁胶囊。
 */
@Composable
fun PlayerCenterControls(
    isVisible: Boolean,
    isLocked: Boolean,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 1. 锁屏状态：中央呈现醒目的解锁挂锁按钮
        AnimatedVisibility(
            visible = isLocked,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.85f)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.72f),
                border = BorderStroke(1.2.dp, Color(0xFFFFB74D)),
                shadowElevation = 10.dp,
                modifier = Modifier.size(56.dp)
            ) {
                IconButton(onClick = onToggleLock) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Unlock Screen",
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // 2. 正常态：中央大播放键 + 快退/快进集群
        AnimatedVisibility(
            visible = isVisible && !isLocked,
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.88f),
            exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.88f)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 快退 10s
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.size(46.dp)
                ) {
                    IconButton(onClick = onRewind10) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 核心大播放/暂停按键 (60dp)
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(60.dp)
                ) {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // 快进 10s
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f),
                    border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.size(46.dp)
                ) {
                    IconButton(onClick = onForward10) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
