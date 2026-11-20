package dev.gyro.feature.settings.gestures

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.core.designsystem.component.GyroSwitchRow
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.VerticalSwipeMode
import kotlin.math.abs

@Composable
fun GestureSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GestureSettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GestureSettingsContent(
        config = uiState.config,
        availableSeekSteps = uiState.availableSeekSteps,
        availableSpeedMultipliers = uiState.availableSpeedMultipliers,
        onBack = onBack,
        onSetVerticalSwipeMode = { viewModel.setVerticalSwipeMode(it) },
        onSetVolumeEnabled = { viewModel.setVolumeGestureEnabled(it) },
        onSetBrightnessEnabled = { viewModel.setBrightnessGestureEnabled(it) },
        onSetInvertSwipeDirections = { viewModel.setInvertSwipeDirections(it) },
        onSetSeekEnabled = { viewModel.setSeekGestureEnabled(it) },
        onSetDoubleTapSeekEnabled = { viewModel.setDoubleTapSeekEnabled(it) },
        onSetDoubleTapSeekSeconds = { viewModel.setDoubleTapSeekSeconds(it) },
        onSetDoubleTapPlayPauseEnabled = { viewModel.setDoubleTapPlayPauseEnabled(it) },
        onSetLongPressSpeedEnabled = { viewModel.setLongPressSpeedEnabled(it) },
        onSetLongPressSpeedMultiplier = { viewModel.setLongPressSpeedMultiplier(it) },
        onSetPinchZoomEnabled = { viewModel.setPinchZoomEnabled(it) },
        onResetToDefault = { viewModel.resetToDefault() },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettingsContent(
    config: PlayerGestureConfig,
    availableSeekSteps: List<Int>,
    availableSpeedMultipliers: List<Float>,
    onBack: () -> Unit,
    onSetVerticalSwipeMode: (VerticalSwipeMode) -> Unit = {},
    onSetVolumeEnabled: (Boolean) -> Unit,
    onSetBrightnessEnabled: (Boolean) -> Unit,
    onSetInvertSwipeDirections: (Boolean) -> Unit,
    onSetSeekEnabled: (Boolean) -> Unit,
    onSetDoubleTapSeekEnabled: (Boolean) -> Unit,
    onSetDoubleTapSeekSeconds: (Int) -> Unit,
    onSetDoubleTapPlayPauseEnabled: (Boolean) -> Unit,
    onSetLongPressSpeedEnabled: (Boolean) -> Unit,
    onSetLongPressSpeedMultiplier: (Float) -> Unit,
    onSetPinchZoomEnabled: (Boolean) -> Unit,
    onResetToDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GyroColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.gesture_settings_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = GyroColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = GyroIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                            tint = GyroColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirmDialog = true }) {
                        Icon(
                            imageVector = GyroIcons.RestartAlt,
                            contentDescription = stringResource(R.string.settings_gesture_reset_defaults),
                            tint = GyroColors.TextSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GyroColors.TopBarBackground,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 垂直滑动手势（芯片选择模式 + 条件显示音量/亮度细项）
            item {
                val isVolBright = config.verticalSwipeMode == VerticalSwipeMode.BRIGHTNESS_AND_VOLUME
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_gesture_group_vertical))
                    GyroSettingsGroup {
                        GyroSectionHeader(
                            title = stringResource(R.string.settings_gesture_vertical_mode),
                            horizontalPadding = 16.dp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GyroChip(
                                label = stringResource(R.string.settings_gesture_vertical_mode_vol_bright),
                                selected = isVolBright,
                                onClick = { onSetVerticalSwipeMode(VerticalSwipeMode.BRIGHTNESS_AND_VOLUME) },
                                modifier = Modifier.weight(1f),
                            )
                            GyroChip(
                                label = stringResource(R.string.settings_gesture_vertical_mode_switch_video),
                                selected = !isVolBright,
                                onClick = { onSetVerticalSwipeMode(VerticalSwipeMode.SWITCH_VIDEO) },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        AnimatedVisibility(
                            visible = isVolBright,
                            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                        ) {
                            Column {
                                GyroGroupDivider()
                                GyroSwitchRow(
                                    title = stringResource(R.string.settings_gesture_volume),
                                    subtitle = stringResource(R.string.settings_gesture_volume_desc),
                                    checked = config.isVolumeEnabled,
                                    onCheckedChange = onSetVolumeEnabled,
                                )
                                GyroGroupDivider()
                                GyroSwitchRow(
                                    title = stringResource(R.string.settings_gesture_brightness),
                                    subtitle = stringResource(R.string.settings_gesture_brightness_desc),
                                    checked = config.isBrightnessEnabled,
                                    onCheckedChange = onSetBrightnessEnabled,
                                )
                                GyroGroupDivider()
                                GyroSwitchRow(
                                    title = stringResource(R.string.settings_gesture_swap_sides),
                                    subtitle = stringResource(R.string.settings_gesture_swap_sides_desc),
                                    checked = config.swapVolumeAndBrightness,
                                    onCheckedChange = onSetInvertSwipeDirections,
                                )
                            }
                        }
                    }
                }
            }

            // 2. 水平与快进手势（开启双击快进时直接展开就地步长芯片，关闭即收起）
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_gesture_group_horizontal))
                    GyroSettingsGroup {
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gesture_seek),
                            subtitle = stringResource(R.string.settings_gesture_seek_desc),
                            checked = config.isSeekEnabled,
                            onCheckedChange = onSetSeekEnabled,
                        )
                        GyroGroupDivider()
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gesture_double_tap_seek),
                            subtitle = stringResource(R.string.settings_gesture_double_tap_seek_desc),
                            checked = config.isDoubleTapSeekEnabled,
                            onCheckedChange = onSetDoubleTapSeekEnabled,
                        )
                        AnimatedVisibility(
                            visible = config.isDoubleTapSeekEnabled,
                            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                        ) {
                            Column {
                                GyroGroupDivider()
                                GyroSectionHeader(
                                    title = stringResource(R.string.settings_gesture_seek_seconds),
                                    horizontalPadding = 16.dp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    availableSeekSteps.forEach { seconds ->
                                        GyroChip(
                                            label = stringResource(R.string.settings_gesture_seek_seconds_val, seconds),
                                            selected = config.doubleTapSeekSeconds == seconds,
                                            onClick = { onSetDoubleTapSeekSeconds(seconds) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 播放控制手势（开启长按倍速时直接展开就地倍速芯片，关闭即收起）
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_gesture_group_playback))
                    GyroSettingsGroup {
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gesture_double_tap_play),
                            subtitle = stringResource(R.string.settings_gesture_double_tap_play_desc),
                            checked = config.isDoubleTapPlayPauseEnabled,
                            onCheckedChange = onSetDoubleTapPlayPauseEnabled,
                        )
                        GyroGroupDivider()
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gesture_long_press_speed),
                            subtitle = stringResource(R.string.settings_gesture_long_press_speed_desc),
                            checked = config.isLongPressSpeedEnabled,
                            onCheckedChange = onSetLongPressSpeedEnabled,
                        )
                        AnimatedVisibility(
                            visible = config.isLongPressSpeedEnabled,
                            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                        ) {
                            Column {
                                GyroGroupDivider()
                                GyroSectionHeader(
                                    title = stringResource(R.string.settings_gesture_speed_multiplier),
                                    horizontalPadding = 16.dp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    availableSpeedMultipliers.forEach { multiplier ->
                                        GyroChip(
                                            label = stringResource(
                                                R.string.settings_gesture_speed_multiplier_val,
                                                multiplier,
                                            ),
                                            selected = abs(config.longPressSpeed - multiplier) < 0.05f,
                                            onClick = { onSetLongPressSpeedMultiplier(multiplier) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. 高级手势
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_gesture_group_advanced))
                    GyroSettingsGroup {
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gesture_zoom),
                            subtitle = stringResource(R.string.settings_gesture_zoom_desc),
                            checked = config.isZoomEnabled,
                            onCheckedChange = onSetPinchZoomEnabled,
                        )
                    }
                }
            }
        }
    }

    // 破坏性重置保留二次确认对话框
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            containerColor = GyroColors.SurfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.settings_gesture_reset_dialog_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GyroColors.TextPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_gesture_reset_dialog_desc),
                    fontSize = 14.sp,
                    color = GyroColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetToDefault()
                        showResetConfirmDialog = false
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_confirm),
                        color = GyroColors.PrimaryLight,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = GyroColors.TextMuted,
                    )
                }
            },
        )
    }
}
