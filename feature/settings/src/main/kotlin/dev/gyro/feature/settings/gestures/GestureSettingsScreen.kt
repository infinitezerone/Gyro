package dev.gyro.feature.settings.gestures

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.VerticalSwipeMode

@Composable
fun GestureSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GestureSettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    var showVerticalModeDialog by remember { mutableStateOf(false) }
    var showSeekStepDialog by remember { mutableStateOf(false) }
    var showSpeedMultiplierDialog by remember { mutableStateOf(false) }
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
                        color = GyroColors.TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = GyroColors.TextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.settings_gesture_reset_defaults),
                            tint = GyroColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GyroColors.TopBarBackground
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Vertical Gestures
            item {
                FloatingSectionHeader(
                    title = stringResource(
                        R.string.settings_gesture_group_vertical
                    )
                )
                FloatingPreferenceClickItem(
                    title = stringResource(R.string.settings_gesture_vertical_mode),
                    value = if (config.verticalSwipeMode == VerticalSwipeMode.SWITCH_VIDEO) {
                        stringResource(R.string.settings_gesture_vertical_mode_switch_video)
                    } else {
                        stringResource(R.string.settings_gesture_vertical_mode_vol_bright)
                    },
                    onClick = { showVerticalModeDialog = true }
                )
                if (config.verticalSwipeMode == VerticalSwipeMode.BRIGHTNESS_AND_VOLUME) {
                    FloatingPreferenceSwitchItem(
                        title = stringResource(R.string.settings_gesture_volume),
                        description = stringResource(R.string.settings_gesture_volume_desc),
                        checked = config.isVolumeEnabled,
                        onCheckedChange = onSetVolumeEnabled
                    )
                    FloatingPreferenceSwitchItem(
                        title = stringResource(R.string.settings_gesture_brightness),
                        description = stringResource(R.string.settings_gesture_brightness_desc),
                        checked = config.isBrightnessEnabled,
                        onCheckedChange = onSetBrightnessEnabled
                    )
                    FloatingPreferenceSwitchItem(
                        title = stringResource(R.string.settings_gesture_swap_sides),
                        description = stringResource(R.string.settings_gesture_swap_sides_desc),
                        checked = config.swapVolumeAndBrightness,
                        onCheckedChange = onSetInvertSwipeDirections
                    )
                }
            }

            // 2. Horizontal & Seeking Gestures
            item {
                FloatingSectionHeader(
                    title = stringResource(
                        R.string.settings_gesture_group_horizontal
                    )
                )
                FloatingPreferenceSwitchItem(
                    title = stringResource(R.string.settings_gesture_seek),
                    description = stringResource(R.string.settings_gesture_seek_desc),
                    checked = config.isSeekEnabled,
                    onCheckedChange = onSetSeekEnabled
                )
                FloatingPreferenceSwitchItem(
                    title = stringResource(R.string.settings_gesture_double_tap_seek),
                    description = stringResource(R.string.settings_gesture_double_tap_seek_desc),
                    checked = config.isDoubleTapSeekEnabled,
                    onCheckedChange = onSetDoubleTapSeekEnabled
                )
                FloatingPreferenceClickItem(
                    title = stringResource(R.string.settings_gesture_seek_seconds),
                    value = stringResource(R.string.settings_gesture_seek_seconds_val, config.doubleTapSeekSeconds),
                    enabled = config.isDoubleTapSeekEnabled,
                    onClick = { showSeekStepDialog = true }
                )
            }

            // 3. Playback Controls
            item {
                FloatingSectionHeader(
                    title = stringResource(
                        R.string.settings_gesture_group_playback
                    )
                )
                FloatingPreferenceSwitchItem(
                    title = stringResource(R.string.settings_gesture_double_tap_play),
                    description = stringResource(R.string.settings_gesture_double_tap_play_desc),
                    checked = config.isDoubleTapPlayPauseEnabled,
                    onCheckedChange = onSetDoubleTapPlayPauseEnabled
                )
                FloatingPreferenceSwitchItem(
                    title = stringResource(R.string.settings_gesture_long_press_speed),
                    description = stringResource(R.string.settings_gesture_long_press_speed_desc),
                    checked = config.isLongPressSpeedEnabled,
                    onCheckedChange = onSetLongPressSpeedEnabled
                )
                FloatingPreferenceClickItem(
                    title = stringResource(R.string.settings_gesture_speed_multiplier),
                    value = stringResource(R.string.settings_gesture_speed_multiplier_val, config.longPressSpeed),
                    enabled = config.isLongPressSpeedEnabled,
                    onClick = { showSpeedMultiplierDialog = true }
                )
            }

            // 4. Advanced Gestures
            item {
                FloatingSectionHeader(
                    title = stringResource(
                        R.string.settings_gesture_group_advanced
                    )
                )
                FloatingPreferenceSwitchItem(
                    title = stringResource(R.string.settings_gesture_zoom),
                    description = stringResource(R.string.settings_gesture_zoom_desc),
                    checked = config.isZoomEnabled,
                    onCheckedChange = onSetPinchZoomEnabled
                )
            }

            // 5. Reset Action Item
            item {
                Spacer(modifier = Modifier.height(8.dp))
                FloatingActionItem(
                    title = stringResource(R.string.settings_gesture_reset_defaults),
                    icon = Icons.Default.RestartAlt,
                    onClick = { showResetConfirmDialog = true }
                )
            }
        }
    }

    // Vertical Swipe Mode Dialog
    if (showVerticalModeDialog) {
        val options = listOf(
            stringResource(R.string.settings_gesture_vertical_mode_vol_bright),
            stringResource(R.string.settings_gesture_vertical_mode_switch_video)
        )
        val selectedIdx = if (config.verticalSwipeMode == VerticalSwipeMode.SWITCH_VIDEO) 1 else 0
        SingleChoiceDialog(
            title = stringResource(R.string.settings_gesture_vertical_mode),
            options = options,
            selectedIndex = selectedIdx,
            onSelect = { idx ->
                val newMode = if (idx == 1) VerticalSwipeMode.SWITCH_VIDEO else VerticalSwipeMode.BRIGHTNESS_AND_VOLUME
                onSetVerticalSwipeMode(newMode)
                showVerticalModeDialog = false
            },
            onDismiss = { showVerticalModeDialog = false }
        )
    }

    // Seek Step Dialog (5s, 10s, 15s, 30s)
    if (showSeekStepDialog) {
        val options = availableSeekSteps
        SingleChoiceDialog(
            title = stringResource(R.string.settings_gesture_seek_seconds),
            options = options.map { stringResource(R.string.settings_gesture_seek_seconds_val, it) },
            selectedIndex = options.indexOf(config.doubleTapSeekSeconds).coerceAtLeast(0),
            onSelect = { idx ->
                onSetDoubleTapSeekSeconds(options[idx])
                showSeekStepDialog = false
            },
            onDismiss = { showSeekStepDialog = false }
        )
    }

    // Speed Multiplier Dialog (1.5x, 2.0x, 3.0x)
    if (showSpeedMultiplierDialog) {
        val options = availableSpeedMultipliers
        SingleChoiceDialog(
            title = stringResource(R.string.settings_gesture_speed_multiplier),
            options = options.map { stringResource(R.string.settings_gesture_speed_multiplier_val, it) },
            selectedIndex = options.indexOfFirst { kotlin.math.abs(it - config.longPressSpeed) < 0.05f }.coerceAtLeast(0),
            onSelect = { idx ->
                onSetLongPressSpeedMultiplier(options[idx])
                showSpeedMultiplierDialog = false
            },
            onDismiss = { showSpeedMultiplierDialog = false }
        )
    }

    // Reset Confirmation Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            containerColor = GyroColors.SurfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.settings_gesture_reset_dialog_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GyroColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_gesture_reset_dialog_desc),
                    fontSize = 14.sp,
                    color = GyroColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetToDefault()
                        showResetConfirmDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_confirm),
                        color = GyroColors.PrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = GyroColors.TextMuted
                    )
                }
            }
        )
    }
}

// --- Pure Floating UI Components ---

@Composable
private fun FloatingSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = GyroColors.PrimaryLight,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun FloatingPreferenceSwitchItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextPrimary
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = GyroColors.TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GyroColors.Primary
            )
        )
    }
}

@Composable
private fun FloatingPreferenceClickItem(
    title: String,
    value: String? = null,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1.0f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextPrimary
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = GyroColors.TextSecondary
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = GyroColors.TextSecondary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = GyroColors.TextMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun FloatingActionItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GyroColors.PrimaryLight,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = GyroColors.PrimaryLight
        )
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GyroColors.SurfaceVariant,
        title = {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = GyroColors.TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = GyroColors.Primary,
                                unselectedColor = GyroColors.TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = option,
                            fontSize = 15.sp,
                            color = GyroColors.TextPrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_back), color = GyroColors.PrimaryLight)
            }
        }
    )
}
