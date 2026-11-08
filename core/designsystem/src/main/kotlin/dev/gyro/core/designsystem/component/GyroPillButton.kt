package dev.gyro.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 现代高反差实体胶囊操作键 (GyroPillButton)。
 *
 * 适用于倍速标签（如 "1.0x"）、选集入口等微胶囊，
 * 封装 ≥48dp 隐式靶心判定与 [expressivePress] 弹性微动效。
 */
@Composable
fun GyroPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 32.dp,
    containerColor: Color = Color.Black.copy(alpha = 0.45f),
    contentColor: Color = Color.White,
    border: BorderStroke? = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .expressivePress(
                    interactionSource = interactionSource,
                    pressedScale = 0.94f,
                    enabled = enabled,
                )
                .height(height)
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
                .clip(CircleShape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                )
            }
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                ),
            )
        }
    }
}
