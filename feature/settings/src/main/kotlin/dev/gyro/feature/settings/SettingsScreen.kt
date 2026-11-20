package dev.gyro.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroInfoRow
import dev.gyro.core.designsystem.component.GyroNavigationRow
import dev.gyro.core.designsystem.component.GyroSectionHeader
import dev.gyro.core.designsystem.component.GyroSettingsGroup
import dev.gyro.core.designsystem.component.GyroSwitchRow
import dev.gyro.core.designsystem.icon.GyroIcons
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.HdrOutputMode
import dev.gyro.core.model.HwdecMode
import dev.gyro.core.model.ToneMappingAlgorithm
import dev.gyro.core.model.UserSettings
import dev.gyro.core.model.VideoScale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenFolderManagement: () -> Unit = {},
    onNavigateToGestures: () -> Unit = {},
    onNavigateToVideoRendering: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenFolderManagement = onOpenFolderManagement,
        onNavigateToGestures = onNavigateToGestures,
        onNavigateToVideoRendering = onNavigateToVideoRendering,
        onUpdateAutoResume = { viewModel.setAutoResume(it) },
        onUpdateAutoPip = { viewModel.setAutoPip(it) },
        onUpdateBackgroundAudio = { viewModel.setBackgroundAudio(it) },
        onUpdateSubAutoLoad = { viewModel.setSubAutoLoad(it) },
        onUpdateGestures = { viewModel.setGesturesEnabled(it) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: UserSettings,
    onBack: () -> Unit,
    onOpenFolderManagement: () -> Unit = {},
    onNavigateToGestures: () -> Unit = {},
    onNavigateToVideoRendering: () -> Unit = {},
    onUpdateAutoResume: (Boolean) -> Unit,
    onUpdateAutoPip: (Boolean) -> Unit = {},
    onUpdateBackgroundAudio: (Boolean) -> Unit = {},
    onUpdateSubAutoLoad: (Boolean) -> Unit,
    onUpdateGestures: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GyroColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
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
            // 1. 播放与媒体库
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_group_playback_library))
                    GyroSettingsGroup {
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_auto_resume),
                            subtitle = stringResource(R.string.settings_auto_resume_desc),
                            icon = GyroIcons.Play,
                            checked = settings.autoResume,
                            onCheckedChange = onUpdateAutoResume,
                        )
                        GyroGroupDivider(insetStart = 46.dp)
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_auto_pip),
                            subtitle = stringResource(R.string.settings_auto_pip_desc),
                            icon = GyroIcons.Pip,
                            checked = settings.autoPip,
                            onCheckedChange = onUpdateAutoPip,
                        )
                        GyroGroupDivider(insetStart = 46.dp)
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_bg_audio),
                            subtitle = stringResource(R.string.settings_bg_audio_desc),
                            icon = GyroIcons.Headphones,
                            checked = settings.backgroundAudio,
                            onCheckedChange = onUpdateBackgroundAudio,
                        )
                        GyroGroupDivider(insetStart = 46.dp)
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_sub_autoload),
                            subtitle = stringResource(R.string.settings_sub_autoload_desc),
                            icon = GyroIcons.Subtitles,
                            checked = settings.subAutoLoad,
                            onCheckedChange = onUpdateSubAutoLoad,
                        )
                        GyroGroupDivider(insetStart = 46.dp)
                        GyroNavigationRow(
                            title = stringResource(R.string.settings_folders_manage),
                            subtitle = stringResource(R.string.settings_folders_manage_desc),
                            icon = GyroIcons.Folder,
                            onClick = onOpenFolderManagement,
                        )
                    }
                }
            }

            // 2. 手势控制（开关联动：关闭总开关时平滑收起明细入口）
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_group_gestures))
                    GyroSettingsGroup {
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_gestures_enabled),
                            subtitle = stringResource(R.string.settings_gestures_enabled_desc),
                            icon = GyroIcons.Tune,
                            checked = settings.gesturesEnabled,
                            onCheckedChange = onUpdateGestures,
                        )
                        AnimatedVisibility(
                            visible = settings.gesturesEnabled,
                            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                            exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                        ) {
                            Column {
                                GyroGroupDivider(insetStart = 46.dp)
                                GyroNavigationRow(
                                    title = stringResource(R.string.settings_group_gestures_detail),
                                    subtitle = stringResource(R.string.settings_group_gestures_detail_desc),
                                    icon = GyroIcons.Settings,
                                    value = stringResource(
                                        R.string.settings_gesture_summary_fmt,
                                        settings.gestureConfig.doubleTapSeekSeconds,
                                        settings.gestureConfig.longPressSpeed,
                                    ),
                                    onClick = onNavigateToGestures,
                                )
                            }
                        }
                    }
                }
            }

            // 3. 解码与视频渲染（渐进式披露：首页只留 1 行带摘要入口，细节迁入二级页）
            item {
                val hwShort = when (settings.defaultHwdecMode) {
                    HwdecMode.HARDWARE_DIRECT -> stringResource(R.string.settings_hwdec_active_mediacodec)
                    HwdecMode.HARDWARE_COPY -> stringResource(R.string.settings_hwdec_active_copy)
                    HwdecMode.SOFTWARE -> stringResource(R.string.settings_hwdec_active_sw)
                }
                val hdrShort = when (settings.hdrOutput) {
                    HdrOutputMode.SDR -> stringResource(R.string.settings_hdr_active_sdr)
                    else -> stringResource(R.string.settings_hdr_active_auto)
                }
                val isRenderingCustomized = settings.defaultHwdecMode != HwdecMode.HARDWARE_DIRECT ||
                    settings.hdrOutput == HdrOutputMode.SDR ||
                    settings.highQualityRendering ||
                    settings.videoScalePreset != VideoScale.BILINEAR ||
                    settings.toneMapping != ToneMappingAlgorithm.AUTO ||
                    settings.interpolation

                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_group_decoder))
                    GyroSettingsGroup {
                        GyroNavigationRow(
                            title = stringResource(R.string.settings_rendering_entry_title),
                            subtitle = stringResource(R.string.settings_rendering_entry_desc),
                            icon = GyroIcons.Memory,
                            value = "$hwShort · $hdrShort",
                            valueHighlighted = isRenderingCustomized,
                            onClick = onNavigateToVideoRendering,
                        )
                    }
                }
            }

            // 4. 关于
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_group_about))
                    GyroSettingsGroup {
                        GyroInfoRow(
                            title = stringResource(R.string.settings_app_version),
                            value = "1.0.0",
                            icon = GyroIcons.Info,
                        )
                        GyroGroupDivider(insetStart = 46.dp)
                        GyroInfoRow(
                            title = stringResource(R.string.settings_engine_core),
                            value = stringResource(R.string.settings_engine_core_val),
                            icon = GyroIcons.Movie,
                        )
                    }
                }
            }
        }
    }
}
