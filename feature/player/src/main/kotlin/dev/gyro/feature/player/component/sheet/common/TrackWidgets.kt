package dev.gyro.feature.player.component.sheet.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import java.util.Locale

@Composable
internal fun TrackItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    accentColor: Color = SheetAccentBlue,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.15f) else SheetCardColor,
        border = if (isSelected) BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) accentColor.copy(alpha = 0.2f) else SheetCardAltColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) accentColor else SheetTextSecondaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) accentColor else SheetTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SheetTextSecondaryColor
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun EmptyTrackNotice(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, color = SheetTextSecondaryColor)
    }
}

/**
 * A/V 同步校准卡：实时状态胶囊 + 细调步进 + 粗调快速芯片。
 */
@Composable
internal fun SyncCalibrationItem(
    label: String,
    currentSec: Double,
    accentColor: Color = SheetAccentBlue,
    onValueChange: (Double) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SheetCardColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Row 1: 标题与实时同步状态胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SheetTextColor
                )

                val formatted = String.format(Locale.US, "%.1fs", currentSec)
                val statusText = when {
                    currentSec > 0.05 -> "延迟 +$formatted"
                    currentSec < -0.05 -> "提前 $formatted"
                    else -> "已同步 0.0s"
                }
                val statusColor = when {
                    Math.abs(currentSec) > 0.05 -> accentColor
                    else -> SheetGreen
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: 细调步进按钮组 [-0.1s] [ 重置 ] [+0.1s]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { onValueChange(currentSec - 0.1) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SheetCardAltColor,
                        contentColor = SheetTextColor
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-0.1s", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                FilledTonalButton(
                    onClick = { onValueChange(0.0) },
                    modifier = Modifier.weight(1.2f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    enabled = Math.abs(currentSec) > 0.01,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = accentColor.copy(alpha = 0.2f),
                        contentColor = accentColor,
                        disabledContainerColor = SheetCardColor,
                        disabledContentColor = SheetTextSecondaryColor
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("重置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                FilledTonalButton(
                    onClick = { onValueChange(currentSec + 0.1) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SheetCardAltColor,
                        contentColor = SheetTextColor
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+0.1s", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: 粗调快速芯片组 [-1.0s] [-0.5s] [+0.5s] [+1.0s]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(-1.0, -0.5, 0.5, 1.0).forEach { delta ->
                    val sign = if (delta > 0) "+" else ""
                    FilledTonalButton(
                        onClick = { onValueChange(currentSec + delta) },
                        modifier = Modifier.weight(1f).height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SheetCardColor,
                            contentColor = SheetTextSecondaryColor
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("$sign${delta}s", fontSize = 11.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
        }
    }
}
