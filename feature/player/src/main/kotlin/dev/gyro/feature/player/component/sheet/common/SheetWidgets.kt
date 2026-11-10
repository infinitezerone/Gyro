package dev.gyro.feature.player.component.sheet.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// —— 统一深色高质感设计规范（与播放器悬浮层、全局设置和暗色沉浸主题完全统一） ——
internal val SheetBgColor = Color(0xFF14161D)
internal val SheetCardColor = Color(0xFF1C202B)
internal val SheetCardAltColor = Color(0xFF242938)
internal val SheetTrackColor = Color(0xFF333A4D)
internal val SheetTextColor = Color.White
internal val SheetTextSecondaryColor = Color.White.copy(alpha = 0.65f)

// 强调色：统一科技蓝，辅以翠绿/暖琥珀
internal val SheetAccentBlue = Color(0xFF2196F3)
internal val SheetGreen = Color(0xFF4CAF50)
internal val SheetAmber = Color(0xFFFFB74D)

/**
 * 设置大厅与二级设置分类分组小标题 (SheetSectionHeader)
 */
@Composable
internal fun SheetSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SheetTextSecondaryColor
        ),
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
    )
}

/**
 * 极简高密度设置项 (SettingsItemRow)
 * - 左边：[ 图标 ] 标题
 * - 右边：当前状态标签 + > (或者直接是 Switch / 操作控件)
 * - 彻底干掉冗余长文本说明，空间利用率 100%
 */
@Composable
internal fun SettingsItemRow(
    title: String,
    icon: ImageVector,
    currentValue: String? = null,
    iconTint: Color = SheetAccentBlue,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val rowModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (trailingContent != null) {
            trailingContent()
        } else if (currentValue != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = currentValue,
                    fontSize = 13.sp,
                    color = SheetTextSecondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    tint = SheetTextSecondaryColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * 紧凑高密度单行配置行 (SettingsActionRow)
 * 左边：图标 + 标题
 * 右边：操作控件（胶囊组、开关、微调器等）
 */
@Composable
internal fun SettingsActionRow(
    title: String,
    icon: ImageVector,
    iconTint: Color = SheetAccentBlue,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor
            )
        }

        Box(contentAlignment = Alignment.CenterEnd) {
            content()
        }
    }
}

/**
 * 统一现代微磨砂胶囊筛选/预设项 (SheetChip)
 */
@Composable
internal fun SheetChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) SheetAccentBlue.copy(alpha = 0.22f) else SheetCardColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) SheetAccentBlue else Color.White.copy(alpha = 0.08f)
        ),
        modifier = modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) SheetAccentBlue else SheetTextColor
            )
        }
    }
}

/**
 * 设置大厅整合卡片容器 (SettingsGroupCard)
 * 将同类设置项聚合成一个高质感圆角组，杜绝零散卡片堆叠。
 */
@Composable
internal fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetCardColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.08f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * 现代轻量级设置行项 (SettingsHubItem)
 */
@Composable
internal fun SettingsHubItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = SheetAccentBlue,
    showDivider: Boolean = false,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = SheetTextColor
                ),
                modifier = Modifier.weight(1f)
            )

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = SheetTextSecondaryColor
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = SheetTextSecondaryColor.copy(alpha = 0.5f),
                modifier = Modifier.size(11.dp)
            )
        }
        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                color = Color.White.copy(alpha = 0.05f),
                modifier = Modifier.padding(start = 46.dp, end = 14.dp)
            )
        }
    }
}

@Composable
internal fun MediaInfoGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SheetCardAltColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = SheetAccentBlue
            )
            content()
        }
    }
}

@Composable
internal fun MediaInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = SheetTextSecondaryColor
        )
        Text(
            text = value,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor,
                fontFeatureSettings = "tnum"
            )
        )
    }
}

@Composable
internal fun FilterSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = SheetTextColor
            )
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SheetTextSecondaryColor
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SheetAccentBlue,
                checkedTrackColor = SheetAccentBlue.copy(alpha = 0.35f),
                uncheckedThumbColor = SheetTrackColor,
                uncheckedTrackColor = SheetCardAltColor
            )
        )
    }
}

@Composable
internal fun ColorSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = SheetTextColor,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = if (value > 0) "+$value" else "$value",
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (value != 0) SheetAccentBlue else SheetTextSecondaryColor,
                    fontFeatureSettings = "tnum"
                )
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(Math.round(it)) },
            valueRange = -100f..100f,
            colors = SliderDefaults.colors(
                thumbColor = SheetAccentBlue,
                activeTrackColor = SheetAccentBlue,
                inactiveTrackColor = SheetTrackColor
            )
        )
    }
}

@Composable
internal fun SheetSectionTitle(
    title: String,
    accentColor: Color = SheetAccentBlue,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = accentColor,
        modifier = modifier
    )
}

@Composable
internal fun FilledTonalAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = SheetCardAltColor,
    contentColor: Color = SheetTextColor,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = SheetCardColor,
            disabledContentColor = SheetTextSecondaryColor
        ),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * 格式化码率字符串 (bps -> Mbps / kbps)
 */
internal fun formatBitrate(bps: Long): String {
    if (bps <= 0) return "--"
    val mbps = bps / 1_000_000.0
    val kbps = bps / 1_000.0
    return if (mbps >= 1.0) {
        String.format(java.util.Locale.US, "%.2f Mbps", mbps)
    } else {
        String.format(java.util.Locale.US, "%.0f kbps", kbps)
    }
}
