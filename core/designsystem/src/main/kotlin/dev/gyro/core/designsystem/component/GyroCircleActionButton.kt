package dev.gyro.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 现代微交互圆形操作按键 (GyroCircleActionButton)。
 *
 * 遵循现代极简与自然光影设计：
 * - 40dp 视觉大圆盘，外层经 [minimumInteractiveComponentSize] 保证 ≥48dp 触控靶区（符合 Fitts 定律与可访问性）；
 * - 搭载弹簧微弹性压感（[expressivePress]），按下时 0.92f 微缩反馈，回弹顺滑无涟漪脏斑；
 * - 纯净半透明磨砂质感 + 0.5dp 微光轮廓。
 */
@Composable
fun GyroCircleActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Black.copy(alpha = 0.45f),
    contentColor: Color = Color.White,
    border: BorderStroke? = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
) {
    GyroCircleActionButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        enabled = enabled,
        size = size,
        containerColor = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        )
    }
}

/**
 * 自定义内容槽变体：用于非单一图标的内容（如加载圈、文本等）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GyroCircleActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Black.copy(alpha = 0.45f),
    contentColor: Color = Color.White,
    border: BorderStroke? = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .expressivePress(
                    interactionSource = interactionSource,
                    pressedScale = 0.92f,
                    enabled = enabled,
                )
                .size(size)
                .background(
                    color = if (enabled) containerColor else containerColor.copy(alpha = 0.25f),
                    shape = CircleShape,
                )
                .then(
                    if (border != null) {
                        Modifier.border(border, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
