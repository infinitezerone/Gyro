package dev.gyro.feature.player.component

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroCapsuleContainer
import dev.gyro.core.designsystem.component.GyroCircleActionButton
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.feature.player.component.sheet.common.SheetAccentBlue
import dev.gyro.feature.player.component.sheet.PlayerSheetRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.gyro.core.designsystem.icon.GyroIcons

/**
 * 顶部导航与快捷入口栏 (PlayerTopBar)。
 *
 * - 沉浸式通透顶栏：置于顶部渐变遮罩中，摒弃生硬的悬浮胶囊框；
 * - 竖屏：返回 + 标题（自适应、中间省略）+ 竖三点更多菜单（极简收口，无 PiP）；
 * - 横屏：返回 + 标题 + 系统状态 + 画中画 + 横三点更多菜单。
 */
@Composable
fun PlayerTopBar(
    title: String,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onEnterPiP: () -> Unit,
    onOpenSheetTab: (PlayerSheetRoute) -> Unit,
    hdrFormat: String = "",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = if (isLandscape) 12.dp else 4.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回键：触控靶心 ≥ 48dp，纯白微发光
            GyroCircleActionButton(
                onClick = onBack,
                icon = GyroIcons.Back,
                contentDescription = stringResource(R.string.action_back),
                size = 38.dp,
                iconSize = 22.dp,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                border = null
            )

            if (isLandscape) {
                Spacer(modifier = Modifier.width(6.dp))
            }

            // 视频标题（垂直居中，带文字阴影保证背景可读性；中间省略保留剧集尾部信息）
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.player_default_title) },
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.85f),
                            blurRadius = 8f
                        )
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (hdrFormat.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    HdrBadge(format = hdrFormat)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isLandscape) {
                // 横屏：系统状态 + 画中画 + 横三点更多菜单。
                SystemStatusIndicator()
                Spacer(modifier = Modifier.width(8.dp))

                GyroCircleActionButton(
                    onClick = onEnterPiP,
                    icon = GyroIcons.Pip,
                    contentDescription = stringResource(R.string.cd_pip),
                    size = 38.dp,
                    iconSize = 22.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                GyroCircleActionButton(
                    onClick = { onOpenSheetTab(PlayerSheetRoute.SETTINGS_HUB) },
                    icon = GyroIcons.More,
                    contentDescription = stringResource(R.string.cd_settings),
                    size = 38.dp,
                    iconSize = 22.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
            } else {
                GyroCircleActionButton(
                    onClick = { onOpenSheetTab(PlayerSheetRoute.SETTINGS_HUB) },
                    icon = GyroIcons.MoreVert,
                    contentDescription = stringResource(R.string.cd_settings),
                    size = 38.dp,
                    iconSize = 22.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    border = null
                )
            }
        }
    }
}

@Composable
fun HdrBadge(format: String, modifier: Modifier = Modifier) {
    val isDovi = format.contains("Dolby", ignoreCase = true)
    val text = if (isDovi) "VISION" else format
    val bgColor = if (isDovi) Color(0x33FFB300) else Color(0x2AFFFFFF)
    val textColor = if (isDovi) Color(0xFFFFC107) else Color(0xFFE0E0E0)
    val borderColor = if (isDovi) Color(0x66FFC107) else Color(0x40FFFFFF)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        border = BorderStroke(0.5.dp, borderColor)
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.5.dp)
        )
    }
}

/**
 * 横屏专属系统状态组件 (SystemStatusIndicator)
 *
 * 参照 MX Player / Infuse / VLC 设计：
 * 全屏横屏播放时系统状态栏被隐藏，控件层唤起时直接在顶栏展示当前真实时间与电池电量，
 * 避免用户下拉通知栏破坏播放沉浸体验或触发误触。
 */
@Composable
private fun SystemStatusIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    var currentTime by remember {
        mutableStateOf(
            if (isPreview) "15:42" else {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            }
        )
    }
    var batteryPercent by remember { mutableIntStateOf(if (isPreview) 88 else -1) }
    var isCharging by remember { mutableStateOf(false) }

    if (!isPreview) {
        LaunchedEffect(Unit) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (isActive) {
                currentTime = timeFormat.format(Date())
                try {
                    val batteryIntent = context.registerReceiver(
                        null,
                        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    )
                    if (batteryIntent != null) {
                        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) {
                            batteryPercent = (level * 100) / scale
                        }
                        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    }
                } catch (_: Throwable) {
                    // Safe guard against background / sandbox restrictions
                }
                delay(10_000L)
            }
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = currentTime,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    blurRadius = 6f
                )
            )
        )

        if (batteryPercent in 0..100) {
            BatteryIndicator(
                percent = batteryPercent,
                isCharging = isCharging
            )
        }
    }
}

/**
 * 极简通透电池图标与电量显示 (BatteryIndicator)
 */
@Composable
private fun BatteryIndicator(
    percent: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier
) {
    val displayPercent = percent.coerceIn(0, 100)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.size(width = 20.dp, height = 10.dp)) {
            val strokeWidth = 1.dp.toPx()
            val cornerRadius = CornerRadius(2.dp.toPx())
            val bodyWidth = size.width - 3.dp.toPx()
            val bodyHeight = size.height

            // 电池外壳
            drawRoundRect(
                color = Color.White.copy(alpha = 0.70f),
                topLeft = Offset(0f, 0f),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth)
            )

            // 电池极耳 (右侧小凸起)
            val capWidth = 1.5.dp.toPx()
            val capHeight = bodyHeight * 0.40f
            val capTop = (bodyHeight - capHeight) / 2f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.70f),
                topLeft = Offset(bodyWidth + 1.dp.toPx(), capTop),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(0.8.dp.toPx())
            )

            // 电池内部电量填充
            val fillPadding = 2.dp.toPx()
            val maxFillWidth = (bodyWidth - fillPadding * 2).coerceAtLeast(0f)
            val currentFillWidth = (maxFillWidth * (displayPercent / 100f)).coerceIn(0f, maxFillWidth)
            val fillColor = when {
                isCharging -> Color(0xFF81C784) // 充电中：软绿色
                displayPercent <= 20 -> Color(0xFFFF7043) // 低电量：警示橙红色
                else -> Color.White.copy(alpha = 0.85f)
            }

            if (currentFillWidth > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(fillPadding, fillPadding),
                    size = Size(currentFillWidth, (bodyHeight - fillPadding * 2).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }

        Text(
            text = "$displayPercent%",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                fontFeatureSettings = "tnum",
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.85f),
                    blurRadius = 6f
                )
            )
        )
    }
}
