package dev.gyro.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gyro.core.designsystem.theme.GyroColors

/**
 * 悬浮胶囊容器 (GyroCapsuleContainer)。
 *
 * 播放器控制层的统一底衬配方（伪磨砂，零 blur 成本、全版本可用）：
 * - 填充 [GyroColors.OverlayFill]（black 55%），1px [GyroColors.OverlayStroke] 微光描边；
 * - 柔和投影（默认 8dp）产生"浮在视频上"的层次感；
 * - 默认全胶囊圆角（100%），宽面板场景可传入自定义 [shape]（如 24dp 圆角矩形）。
 */
@Composable
fun GyroCapsuleContainer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(100),
    elevation: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = GyroColors.OverlayFill,
        contentColor = Color.White,
        border = BorderStroke(0.5.dp, GyroColors.OverlayStroke),
        shadowElevation = elevation,
        content = content
    )
}
