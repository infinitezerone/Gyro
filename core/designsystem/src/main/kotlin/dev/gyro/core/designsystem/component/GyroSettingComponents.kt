package dev.gyro.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.designsystem.theme.GyroColors
import kotlin.math.abs

/**
 * 统一的分组卡片容器：圆角 12dp、细描边与深色浮层底色，消除散乱卡片堆叠。
 */
@Composable
fun GyroSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GyroColors.PanelCard,
        border = BorderStroke(0.5.dp, GyroColors.PanelBorder),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/**
 * 分组内部细分割线，支持与带图标行左对齐的缩进。
 */
@Composable
fun GyroGroupDivider(
    modifier: Modifier = Modifier,
    insetStart: Dp = 16.dp,
    insetEnd: Dp = 16.dp,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = insetStart, end = insetEnd),
        thickness = 0.5.dp,
        color = GyroColors.PanelDivider,
    )
}

/**
 * 分区标题行，可选右侧重置/辅助文本按钮。
 */
@Composable
fun GyroSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    horizontalPadding: Dp = 20.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = GyroColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onActionClick != null && actionEnabled) {
            Text(
                text = actionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = GyroColors.PrimaryLight,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onActionClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 统一尺寸与配色的开关控件；配合 [GyroSwitchRow] 时传 `onCheckedChange = null` 让整行统一响应点击。
 */
@Composable
fun GyroSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = GyroColors.Primary,
            uncheckedThumbColor = GyroColors.TextSecondary,
            uncheckedTrackColor = GyroColors.PanelTrack,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
            disabledCheckedTrackColor = GyroColors.Primary.copy(alpha = 0.4f),
            disabledUncheckedThumbColor = GyroColors.TextMuted,
            disabledUncheckedTrackColor = GyroColors.PanelTrack.copy(alpha = 0.5f),
            disabledUncheckedBorderColor = Color.Transparent,
        ),
        modifier = modifier.scale(0.84f),
    )
}

/**
 * 标准开关行：强制整行可点击切换，消除局部才能点开关的割裂感。
 */
@Composable
fun GyroSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GyroRowIconAndLabel(
            title = title,
            subtitle = subtitle,
            icon = icon,
            titleColor = GyroColors.TextPrimary,
            iconTint = GyroColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        GyroSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

/**
 * 标准导航/状态展示行：左侧图标与标题，右侧当前状态摘要（非默认可高亮）与右箭头。
 */
@Composable
fun GyroNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueHighlighted: Boolean = false,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
) {
    val accentTint = if (valueHighlighted) GyroColors.PrimaryLight else GyroColors.TextSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GyroRowIconAndLabel(
            title = title,
            subtitle = subtitle,
            icon = icon,
            titleColor = GyroColors.TextPrimary,
            iconTint = accentTint,
            modifier = Modifier.weight(1f),
        )
        if (value != null || showChevron) {
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 180.dp),
            ) {
                if (value != null) {
                    Text(
                        text = value,
                        fontSize = 13.sp,
                        fontWeight = if (valueHighlighted) FontWeight.Medium else FontWeight.Normal,
                        color = if (valueHighlighted) {
                            GyroColors.PrimaryLight
                        } else {
                            GyroColors.TextSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (showChevron) {
                    Icon(
                        imageVector = GyroIcons.ChevronRight,
                        contentDescription = null,
                        tint = GyroColors.TextSecondary.copy(alpha = 0.55f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 标准单选行：高亮选中底色与主文字，右侧统一 ✓ 标记。
 */
@Composable
fun GyroRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .background(if (selected) GyroColors.Primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GyroRowIconAndLabel(
            title = title,
            subtitle = subtitle,
            icon = icon,
            titleColor = if (selected) GyroColors.PrimaryLight else GyroColors.TextPrimary,
            iconTint = if (selected) GyroColors.PrimaryLight else GyroColors.TextSecondary,
            titleWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = GyroIcons.Check,
                contentDescription = null,
                tint = GyroColors.PrimaryLight,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 统一筛选/预设圆角胶囊芯片。
 */
@Composable
fun GyroChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) GyroColors.Primary.copy(alpha = 0.20f) else GyroColors.PanelCardElevated,
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) GyroColors.Primary else GyroColors.PanelBorder,
        ),
        modifier = modifier.height(34.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (selected) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) GyroColors.PrimaryLight else GyroColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 渐进式披露核心折叠容器：默认只呈现标题与当前摘要，点击后平滑展开低频或微调内容。
 */
@Composable
fun GyroExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summaryHighlighted: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "ExpandChevronRotation",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GyroRowIconAndLabel(
                title = title,
                subtitle = null,
                icon = icon,
                titleColor = GyroColors.TextPrimary,
                iconTint = if (summaryHighlighted) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.widthIn(max = 180.dp),
            ) {
                if (summary != null) {
                    Text(
                        text = summary,
                        fontSize = 13.sp,
                        fontWeight = if (summaryHighlighted) FontWeight.Medium else FontWeight.Normal,
                        color = if (summaryHighlighted) {
                            GyroColors.PrimaryLight
                        } else {
                            GyroColors.TextSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Icon(
                    imageVector = GyroIcons.ChevronRight,
                    contentDescription = null,
                    tint = GyroColors.TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(animationSpec = tween(120)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                GyroGroupDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * 统一连续微调行：上行标签 + 等宽当前数值；下行 `-` 步进 + 带基准吸附的滑块 + `+` 步进。
 */
@Composable
fun GyroFineTuneSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 1f,
    snapValue: Float? = null,
    snapThreshold: Float = 0f,
    isModified: Boolean = false,
    onStepDown: (() -> Unit)? = null,
    onStepUp: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    var lastSliderReported by remember(value) { mutableFloatStateOf(value) }
    val clampedSliderValue = value.coerceIn(valueRange.start, valueRange.endInclusive)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextPrimary,
            )
            Text(
                text = valueText,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isModified) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FineTuneStepButton(
                symbol = "\u2212",
                onClick = {
                    if (onStepDown != null) {
                        onStepDown()
                    } else {
                        onValueChange((value - step).coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                },
            )

            Slider(
                value = clampedSliderValue,
                onValueChange = { raw ->
                    val resolved = if (snapValue != null &&
                        snapThreshold > 0f &&
                        abs(raw - snapValue) <= snapThreshold
                    ) {
                        snapValue
                    } else {
                        raw
                    }
                    val crossedSnap = snapValue != null &&
                        abs(resolved - snapValue) < 1e-4f &&
                        abs(lastSliderReported - snapValue) >= 1e-4f
                    if (crossedSnap) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastSliderReported = resolved
                    onValueChange(resolved)
                },
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = GyroColors.PrimaryLight,
                    activeTrackColor = GyroColors.Primary,
                    inactiveTrackColor = GyroColors.PanelTrack,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )

            FineTuneStepButton(
                symbol = "+",
                onClick = {
                    if (onStepUp != null) {
                        onStepUp()
                    } else {
                        onValueChange((value + step).coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                },
            )
        }
    }
}

/**
 * 抽屉顶部四格快捷瓦片按钮。
 */
@Composable
fun GyroQuickTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) GyroColors.Primary.copy(alpha = 0.18f) else GyroColors.PanelCard,
        border = BorderStroke(
            width = if (selected) 1.dp else 0.5.dp,
            color = if (selected) GyroColors.Primary else GyroColors.PanelBorder,
        ),
        modifier = modifier.height(62.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = badge?.takeIf { it.isNotBlank() } ?: label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) GyroColors.PrimaryLight else GyroColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 标准只读信息展示行（如版本、内核信息）。
 */
@Composable
fun GyroInfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GyroRowIconAndLabel(
            title = title,
            subtitle = null,
            icon = icon,
            titleColor = GyroColors.TextPrimary,
            iconTint = GyroColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = TextStyle(
                fontSize = 13.sp,
                color = GyroColors.TextSecondary,
                fontFeatureSettings = "tnum",
            ),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

/**
 * 底部单选列表弹窗的数据项模型。
 */
data class GyroOptionItem(
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 统一底部单选列表抽屉：淘汰全局设置里风格割裂的居中 AlertDialog。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GyroOptionBottomSheet(
    title: String,
    options: List<GyroOptionItem>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GyroColors.PanelBackground,
        contentColor = GyroColors.TextPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GyroColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = GyroIcons.Close,
                        contentDescription = null,
                        tint = GyroColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            GyroSettingsGroup {
                options.forEachIndexed { index, option ->
                    if (index > 0) GyroGroupDivider()
                    GyroRadioRow(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = option.selected,
                        enabled = option.enabled,
                        onClick = {
                            onSelect(index)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GyroRowIconAndLabel(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    titleColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    titleWeight: FontWeight = FontWeight.Medium,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = titleWeight,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = GyroColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FineTuneStepButton(
    symbol: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = GyroColors.PanelCardElevated,
        border = BorderStroke(0.5.dp, GyroColors.PanelBorder),
        modifier = Modifier.size(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = GyroColors.PrimaryLight,
            )
        }
    }
}
