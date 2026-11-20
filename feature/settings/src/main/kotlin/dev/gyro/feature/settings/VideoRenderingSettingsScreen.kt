package dev.gyro.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.component.GyroChip
import dev.gyro.core.designsystem.component.GyroExpandableSection
import dev.gyro.core.designsystem.component.GyroGroupDivider
import dev.gyro.core.designsystem.component.GyroNavigationRow
import dev.gyro.core.designsystem.component.GyroOptionBottomSheet
import dev.gyro.core.designsystem.component.GyroOptionItem
import dev.gyro.core.designsystem.component.GyroRadioRow
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
fun VideoRenderingSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToMpvConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    VideoRenderingSettingsContent(
        settings = settings,
        onBack = onBack,
        onNavigateToMpvConfig = onNavigateToMpvConfig,
        onUpdateHwdec = { viewModel.setDefaultHwdec(it) },
        onUpdateHdrOutput = { viewModel.setHdrOutputMode(it) },
        onUpdateHighQuality = { viewModel.setHighQualityRendering(it) },
        onUpdateVideoScale = { viewModel.setVideoScale(it) },
        onUpdateToneMapping = { viewModel.setToneMappingAlgorithm(it) },
        onUpdateInterpolation = { viewModel.setInterpolation(it) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoRenderingSettingsContent(
    settings: UserSettings,
    onBack: () -> Unit,
    onNavigateToMpvConfig: () -> Unit = {},
    onUpdateHwdec: (HwdecMode) -> Unit,
    onUpdateHdrOutput: (HdrOutputMode) -> Unit,
    onUpdateHighQuality: (Boolean) -> Unit,
    onUpdateVideoScale: (VideoScale) -> Unit,
    onUpdateToneMapping: (ToneMappingAlgorithm) -> Unit,
    onUpdateInterpolation: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showScaleSheet by remember { mutableStateOf(false) }
    var showToneMappingSheet by remember { mutableStateOf(false) }

    val scaleOptions = listOf(VideoScale.BILINEAR, VideoScale.LANCZOS, VideoScale.SPLINE36)
    val toneAlgorithms = listOf(
        ToneMappingAlgorithm.AUTO,
        ToneMappingAlgorithm.HABLE,
        ToneMappingAlgorithm.BT_2446A,
        ToneMappingAlgorithm.SPLINE,
        ToneMappingAlgorithm.REINHARD,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GyroColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_group_decoder),
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
            // 1. 默认解码器（带说明直接列表勾选，淘汰弹窗）
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_default_hwdec))
                    GyroSettingsGroup {
                        GyroRadioRow(
                            title = stringResource(R.string.settings_hwdec_mediacodec),
                            subtitle = stringResource(R.string.settings_hwdec_mediacodec_desc),
                            selected = settings.defaultHwdecMode == HwdecMode.HARDWARE_DIRECT,
                            onClick = { onUpdateHwdec(HwdecMode.HARDWARE_DIRECT) },
                        )
                        GyroGroupDivider()
                        GyroRadioRow(
                            title = stringResource(R.string.settings_hwdec_copy),
                            subtitle = stringResource(R.string.settings_hwdec_copy_desc),
                            selected = settings.defaultHwdecMode == HwdecMode.HARDWARE_COPY,
                            onClick = { onUpdateHwdec(HwdecMode.HARDWARE_COPY) },
                        )
                        GyroGroupDivider()
                        GyroRadioRow(
                            title = stringResource(R.string.settings_hwdec_sw),
                            subtitle = stringResource(R.string.settings_hwdec_sw_desc),
                            selected = settings.defaultHwdecMode == HwdecMode.SOFTWARE,
                            onClick = { onUpdateHwdec(HwdecMode.SOFTWARE) },
                        )
                    }
                }
            }

            // 2. HDR 与画质基础
            item {
                val isSdr = settings.hdrOutput == HdrOutputMode.SDR
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_hdr_output))
                    GyroSettingsGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GyroChip(
                                label = stringResource(R.string.settings_hdr_output_auto),
                                selected = !isSdr,
                                onClick = { onUpdateHdrOutput(HdrOutputMode.AUTO) },
                                modifier = Modifier.weight(1f),
                            )
                            GyroChip(
                                label = stringResource(R.string.settings_hdr_output_sdr),
                                selected = isSdr,
                                onClick = { onUpdateHdrOutput(HdrOutputMode.SDR) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = stringResource(
                                if (isSdr) {
                                    R.string.settings_hdr_output_sdr_desc
                                } else {
                                    R.string.settings_hdr_output_auto_desc
                                }
                            ),
                            fontSize = 12.sp,
                            color = GyroColors.TextSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                        )

                        GyroGroupDivider()

                        GyroSwitchRow(
                            title = stringResource(R.string.settings_high_quality_rendering),
                            subtitle = stringResource(R.string.settings_high_quality_rendering_desc),
                            checked = settings.highQualityRendering,
                            onCheckedChange = onUpdateHighQuality,
                        )
                    }
                }
            }

            // 3. 高级渲染（渐进式披露：默认收起，带状态摘要与非默认高亮）
            item {
                val scaleShort = videoScaleShortLabel(settings.videoScalePreset)
                val toneShort = toneMappingShortLabel(settings.toneMapping)
                val advancedModified = settings.videoScalePreset != VideoScale.BILINEAR ||
                    settings.toneMapping != ToneMappingAlgorithm.AUTO ||
                    settings.interpolation
                val advancedSummary = if (!advancedModified) {
                    stringResource(R.string.sheet_status_default)
                } else {
                    buildList {
                        if (settings.videoScalePreset != VideoScale.BILINEAR) add(scaleShort)
                        if (settings.toneMapping != ToneMappingAlgorithm.AUTO) add(toneShort)
                        if (settings.interpolation) add(stringResource(R.string.settings_interpolation))
                    }.joinToString(" · ")
                }

                GyroSettingsGroup {
                    GyroExpandableSection(
                        title = stringResource(R.string.settings_advanced_rendering),
                        icon = GyroIcons.AutoAwesome,
                        summary = advancedSummary,
                        summaryHighlighted = advancedModified,
                        expanded = advancedExpanded,
                        onToggle = { advancedExpanded = !advancedExpanded },
                    ) {
                        GyroNavigationRow(
                            title = stringResource(R.string.settings_video_scale),
                            value = scaleShort,
                            valueHighlighted = settings.videoScalePreset != VideoScale.BILINEAR,
                            onClick = { showScaleSheet = true },
                        )
                        GyroGroupDivider()
                        GyroNavigationRow(
                            title = stringResource(R.string.stats_tone_mapping),
                            value = toneShort,
                            valueHighlighted = settings.toneMapping != ToneMappingAlgorithm.AUTO,
                            onClick = { showToneMappingSheet = true },
                        )
                        GyroGroupDivider()
                        GyroSwitchRow(
                            title = stringResource(R.string.settings_interpolation),
                            subtitle = stringResource(R.string.settings_interpolation_desc),
                            checked = settings.interpolation,
                            onCheckedChange = onUpdateInterpolation,
                        )
                    }
                }
            }

            // 4. 专家配置
            item {
                Column {
                    GyroSectionHeader(title = stringResource(R.string.settings_group_expert))
                    GyroSettingsGroup {
                        GyroNavigationRow(
                            title = stringResource(R.string.settings_mpv_conf),
                            subtitle = stringResource(R.string.settings_mpv_conf_desc),
                            icon = GyroIcons.Tune,
                            onClick = onNavigateToMpvConfig,
                        )
                    }
                }
            }
        }
    }

    if (showScaleSheet) {
        GyroOptionBottomSheet(
            title = stringResource(R.string.settings_video_scale),
            options = listOf(
                GyroOptionItem(
                    title = stringResource(R.string.settings_video_scale_bilinear_item),
                    subtitle = stringResource(R.string.video_scale_bilinear_desc),
                    selected = settings.videoScalePreset == VideoScale.BILINEAR,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.settings_video_scale_lanczos_item),
                    subtitle = stringResource(R.string.video_scale_lanczos_desc),
                    selected = settings.videoScalePreset == VideoScale.LANCZOS,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.settings_video_scale_spline36_item),
                    subtitle = stringResource(R.string.video_scale_spline36_desc),
                    selected = settings.videoScalePreset == VideoScale.SPLINE36,
                ),
            ),
            onSelect = { idx -> onUpdateVideoScale(scaleOptions[idx]) },
            onDismiss = { showScaleSheet = false },
        )
    }

    if (showToneMappingSheet) {
        GyroOptionBottomSheet(
            title = stringResource(R.string.stats_tone_mapping),
            options = listOf(
                GyroOptionItem(
                    title = stringResource(R.string.tone_map_auto),
                    subtitle = stringResource(R.string.tone_map_auto_desc),
                    selected = settings.toneMapping == ToneMappingAlgorithm.AUTO,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.tone_map_hable),
                    subtitle = stringResource(R.string.tone_map_hable_desc),
                    selected = settings.toneMapping == ToneMappingAlgorithm.HABLE,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.tone_map_bt2446a),
                    subtitle = stringResource(R.string.tone_map_bt2446a_desc),
                    selected = settings.toneMapping == ToneMappingAlgorithm.BT_2446A,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.tone_map_spline),
                    subtitle = stringResource(R.string.tone_map_spline_desc),
                    selected = settings.toneMapping == ToneMappingAlgorithm.SPLINE,
                ),
                GyroOptionItem(
                    title = stringResource(R.string.tone_map_reinhard),
                    subtitle = stringResource(R.string.tone_map_reinhard_desc),
                    selected = settings.toneMapping == ToneMappingAlgorithm.REINHARD,
                ),
            ),
            onSelect = { idx -> onUpdateToneMapping(toneAlgorithms[idx]) },
            onDismiss = { showToneMappingSheet = false },
        )
    }
}

private fun videoScaleShortLabel(scale: VideoScale): String = when (scale) {
    VideoScale.BILINEAR -> "Bilinear"
    VideoScale.LANCZOS -> "Lanczos"
    VideoScale.SPLINE36 -> "Spline36"
}

@Composable
private fun toneMappingShortLabel(algorithm: ToneMappingAlgorithm): String = when (algorithm) {
    ToneMappingAlgorithm.AUTO -> stringResource(R.string.tone_map_auto)
    ToneMappingAlgorithm.HABLE -> stringResource(R.string.tone_map_hable)
    ToneMappingAlgorithm.BT_2446A -> stringResource(R.string.tone_map_bt2446a)
    ToneMappingAlgorithm.SPLINE -> stringResource(R.string.tone_map_spline)
    ToneMappingAlgorithm.REINHARD -> stringResource(R.string.tone_map_reinhard)
}
