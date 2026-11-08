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

// —— Sheet 简约白主题（二级界面走明亮白调，与播放器浮层的暗玻璃形成节奏对比） ——
internal val SheetBgColor = Color(0xFFFAFAFA)
internal val SheetCardColor = Color(0xFFF1F3F5)
internal val SheetCardAltColor = Color(0xFFE9ECEF)
internal val SheetTrackColor = Color(0xFFD6DAE0)
internal val SheetTextColor = Color(0xFF1C1E21)
internal val SheetTextSecondaryColor = Color(0xFF5F6570)

// 强调色收敛为蓝系一个，辅以可读性更强的深绿/深琥珀
internal val SheetAccentBlue = Color(0xFF1976D2)
internal val SheetGreen = Color(0xFF2E7D32)
internal val SheetAmber = Color(0xFFB26A00)

/**
 * 现代轻量级设置卡片项 (SettingsHubItem)
 */
@Composable
internal fun SettingsHubItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = SheetAccentBlue,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = SheetCardColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SheetTextColor
                    )
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = SheetTextSecondaryColor
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = SheetTextSecondaryColor,
                modifier = Modifier.size(13.dp)
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
