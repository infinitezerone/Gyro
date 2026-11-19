package dev.gyro.feature.settings

import dev.gyro.core.designsystem.R

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    onNavigateToMpvConfig: () -> Unit = {},
    onNavigateToGestures: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenFolderManagement = onOpenFolderManagement,
        onNavigateToMpvConfig = onNavigateToMpvConfig,
        onNavigateToGestures = onNavigateToGestures,
        onUpdateAutoResume = { viewModel.setAutoResume(it) },
        onUpdateAutoPip = { viewModel.setAutoPip(it) },
        onUpdateBackgroundAudio = { viewModel.setBackgroundAudio(it) },
        onUpdateSeekStep = { viewModel.setSeekStepSeconds(it) },
        onUpdateHwdec = { viewModel.setDefaultHwdec(it) },
        onUpdateHdrOutput = { viewModel.setHdrOutputMode(it) },
        onUpdateHighQuality = { viewModel.setHighQualityRendering(it) },
        onUpdateVideoScale = { viewModel.setVideoScale(it) },
        onUpdateToneMapping = { viewModel.setToneMappingAlgorithm(it) },
        onUpdateInterpolation = { viewModel.setInterpolation(it) },
        onUpdateSubAutoLoad = { viewModel.setSubAutoLoad(it) },
        onUpdateGestures = { viewModel.setGesturesEnabled(it) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: UserSettings,
    onBack: () -> Unit,
    onOpenFolderManagement: () -> Unit = {},
    onNavigateToMpvConfig: () -> Unit = {},
    onNavigateToGestures: () -> Unit = {},
    onUpdateAutoResume: (Boolean) -> Unit,
    onUpdateAutoPip: (Boolean) -> Unit = {},
    onUpdateBackgroundAudio: (Boolean) -> Unit = {},
    onUpdateSeekStep: (Int) -> Unit,
    onUpdateHwdec: (HwdecMode) -> Unit,
    onUpdateHdrOutput: (HdrOutputMode) -> Unit = {},
    onUpdateHighQuality: (Boolean) -> Unit = {},
    onUpdateVideoScale: (VideoScale) -> Unit = {},
    onUpdateToneMapping: (ToneMappingAlgorithm) -> Unit = {},
    onUpdateInterpolation: (Boolean) -> Unit = {},
    onUpdateSubAutoLoad: (Boolean) -> Unit,
    onUpdateGestures: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSeekStepDialog by remember { mutableStateOf(false) }
    var showHwdecDialog by remember { mutableStateOf(false) }
    var showHdrDialog by remember { mutableStateOf(false) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showToneMappingDialog by remember { mutableStateOf(false) }

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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 播放设置
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_playback)) {
                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_auto_resume),
                        description = stringResource(R.string.settings_auto_resume_desc),
                        checked = settings.autoResume,
                        onCheckedChange = onUpdateAutoResume
                    )

                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_auto_pip),
                        description = stringResource(R.string.settings_auto_pip_desc),
                        checked = settings.autoPip,
                        onCheckedChange = onUpdateAutoPip
                    )

                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_bg_audio),
                        description = stringResource(R.string.settings_bg_audio_desc),
                        checked = settings.backgroundAudio,
                        onCheckedChange = onUpdateBackgroundAudio
                    )

                    PreferenceClickItem(
                        title = stringResource(R.string.settings_seek_step),
                        value = stringResource(R.string.settings_seek_step_val, settings.seekStepSeconds),
                        onClick = { showSeekStepDialog = true }
                    )
                }
            }

            // 2. 解码与视频渲染
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_decoder)) {
                    val (hwdecLabel, hwdecDesc) = when (settings.defaultHwdecMode) {
                        HwdecMode.HARDWARE_DIRECT ->
                            "MediaCodec 硬解" to stringResource(R.string.settings_hwdec_active_mediacodec)
                        HwdecMode.HARDWARE_COPY ->
                            "MediaCodec-Copy" to stringResource(R.string.settings_hwdec_active_copy)
                        HwdecMode.SOFTWARE ->
                            "CPU 软解" to stringResource(R.string.settings_hwdec_active_sw)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_default_hwdec),
                        value = hwdecLabel,
                        description = hwdecDesc,
                        onClick = { showHwdecDialog = true }
                    )
                    val (hdrLabel, hdrDesc) = when (settings.hdrOutput) {
                        HdrOutputMode.SDR ->
                            "色调映射 (SDR)" to stringResource(R.string.settings_hdr_active_sdr)
                        else ->
                            "自适应点亮 (真 HDR)" to stringResource(R.string.settings_hdr_active_auto)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_hdr_output),
                        value = hdrLabel,
                        description = hdrDesc,
                        onClick = { showHdrDialog = true }
                    )
                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_high_quality_rendering),
                        description = stringResource(R.string.settings_high_quality_rendering_desc),
                        checked = settings.highQualityRendering,
                        onCheckedChange = onUpdateHighQuality
                    )
                    val scaleLabel = when (settings.videoScalePreset) {
                        VideoScale.BILINEAR -> stringResource(R.string.settings_video_scale_bilinear_item)
                        VideoScale.LANCZOS -> stringResource(R.string.settings_video_scale_lanczos_item)
                        VideoScale.SPLINE36 -> stringResource(R.string.settings_video_scale_spline36_item)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_video_scale),
                        value = scaleLabel,
                        onClick = { showScaleDialog = true }
                    )
                    val toneLabel = when (settings.toneMapping) {
                        ToneMappingAlgorithm.AUTO -> stringResource(R.string.tone_map_auto)
                        ToneMappingAlgorithm.HABLE -> stringResource(R.string.tone_map_hable)
                        ToneMappingAlgorithm.BT_2446A -> stringResource(R.string.tone_map_bt2446a)
                        ToneMappingAlgorithm.SPLINE -> stringResource(R.string.tone_map_spline)
                        ToneMappingAlgorithm.REINHARD -> stringResource(R.string.tone_map_reinhard)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.stats_tone_mapping),
                        value = toneLabel,
                        onClick = { showToneMappingDialog = true }
                    )
                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_interpolation),
                        description = stringResource(R.string.settings_interpolation_desc),
                        checked = settings.interpolation,
                        onCheckedChange = onUpdateInterpolation
                    )
                }
            }

            // 3. 字幕与音频
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_subtitles)) {
                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_sub_autoload),
                        description = stringResource(R.string.settings_sub_autoload_desc),
                        checked = settings.subAutoLoad,
                        onCheckedChange = onUpdateSubAutoLoad
                    )
                }
            }

            // 4. 手势控制
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_gestures)) {
                    PreferenceSwitchItem(
                        title = stringResource(R.string.settings_gestures_enabled),
                        description = stringResource(R.string.settings_gestures_enabled_desc),
                        checked = settings.gesturesEnabled,
                        onCheckedChange = onUpdateGestures
                    )
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_group_gestures_detail),
                        description = stringResource(R.string.settings_group_gestures_detail_desc),
                        onClick = onNavigateToGestures
                    )
                }
            }

            // 5. 高级配置 (mpv.conf)
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_advanced)) {
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_mpv_conf),
                        description = stringResource(R.string.settings_mpv_conf_desc),
                        onClick = onNavigateToMpvConfig
                    )
                }
            }

            // 6. 存储与媒体库
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_storage)) {
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_folders_manage),
                        description = stringResource(R.string.settings_folders_manage_desc),
                        onClick = onOpenFolderManagement
                    )
                }
            }

            // 6. 关于
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_about)) {
                    PreferenceInfoItem(
                        title = stringResource(R.string.settings_app_version),
                        value = "1.0.0"
                    )
                    PreferenceInfoItem(
                        title = stringResource(R.string.settings_engine_core),
                        value = stringResource(R.string.settings_engine_core_val)
                    )
                }
            }
        }
    }

    // 单选弹窗: 快进步长
    if (showSeekStepDialog) {
        val options = listOf(5, 10, 15, 30)
        SingleChoiceDialog(
            title = stringResource(R.string.settings_seek_step),
            options = options.map { DialogOption(stringResource(R.string.settings_seek_step_val, it)) },
            selectedIndex = options.indexOf(settings.seekStepSeconds).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateSeekStep(options[idx])
                showSeekStepDialog = false
            },
            onDismiss = { showSeekStepDialog = false }
        )
    }



    // 单选弹窗: 默认解码器
    if (showHwdecDialog) {
        val hwdecModes = listOf(
            HwdecMode.HARDWARE_DIRECT,
            HwdecMode.HARDWARE_COPY,
            HwdecMode.SOFTWARE
        )
        val options = listOf(
            DialogOption(
                title = stringResource(R.string.settings_hwdec_mediacodec),
                description = stringResource(R.string.settings_hwdec_mediacodec_desc)
            ),
            DialogOption(
                title = stringResource(R.string.settings_hwdec_copy),
                description = stringResource(R.string.settings_hwdec_copy_desc)
            ),
            DialogOption(
                title = stringResource(R.string.settings_hwdec_sw),
                description = stringResource(R.string.settings_hwdec_sw_desc)
            )
        )
        SingleChoiceDialog(
            title = stringResource(R.string.settings_default_hwdec),
            options = options,
            selectedIndex = hwdecModes.indexOf(settings.defaultHwdecMode).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateHwdec(hwdecModes[idx])
                showHwdecDialog = false
            },
            onDismiss = { showHwdecDialog = false }
        )
    }

    // 单选弹窗: HDR 输出模式
    if (showHdrDialog) {
        val modes = listOf(HdrOutputMode.AUTO, HdrOutputMode.SDR)
        val options = listOf(
            DialogOption(
                title = stringResource(R.string.settings_hdr_output_auto),
                description = stringResource(R.string.settings_hdr_output_auto_desc)
            ),
            DialogOption(
                title = stringResource(R.string.settings_hdr_output_sdr),
                description = stringResource(R.string.settings_hdr_output_sdr_desc)
            )
        )
        SingleChoiceDialog(
            title = stringResource(R.string.settings_hdr_output),
            options = options,
            selectedIndex = modes.indexOf(settings.hdrOutput).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateHdrOutput(modes[idx])
                showHdrDialog = false
            },
            onDismiss = { showHdrDialog = false }
        )
    }

    // 单选弹窗: 缩放插值算法
    if (showScaleDialog) {
        val scales = listOf(VideoScale.BILINEAR, VideoScale.LANCZOS, VideoScale.SPLINE36)
        val options = listOf(
            DialogOption(
                title = stringResource(R.string.settings_video_scale_bilinear_item),
                description = stringResource(R.string.video_scale_bilinear_desc)
            ),
            DialogOption(
                title = stringResource(R.string.settings_video_scale_lanczos_item),
                description = stringResource(R.string.video_scale_lanczos_desc)
            ),
            DialogOption(
                title = stringResource(R.string.settings_video_scale_spline36_item),
                description = stringResource(R.string.video_scale_spline36_desc)
            )
        )
        SingleChoiceDialog(
            title = stringResource(R.string.settings_video_scale),
            options = options,
            selectedIndex = scales.indexOf(settings.videoScalePreset).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateVideoScale(scales[idx])
                showScaleDialog = false
            },
            onDismiss = { showScaleDialog = false }
        )
    }

    // 单选弹窗: HDR 色调映射曲线
    if (showToneMappingDialog) {
        val algorithms = listOf(
            ToneMappingAlgorithm.AUTO,
            ToneMappingAlgorithm.HABLE,
            ToneMappingAlgorithm.BT_2446A,
            ToneMappingAlgorithm.SPLINE,
            ToneMappingAlgorithm.REINHARD
        )
        val options = listOf(
            DialogOption(
                title = stringResource(R.string.tone_map_auto),
                description = stringResource(R.string.tone_map_auto_desc)
            ),
            DialogOption(
                title = stringResource(R.string.tone_map_hable),
                description = stringResource(R.string.tone_map_hable_desc)
            ),
            DialogOption(
                title = stringResource(R.string.tone_map_bt2446a),
                description = stringResource(R.string.tone_map_bt2446a_desc)
            ),
            DialogOption(
                title = stringResource(R.string.tone_map_spline),
                description = stringResource(R.string.tone_map_spline_desc)
            ),
            DialogOption(
                title = stringResource(R.string.tone_map_reinhard),
                description = stringResource(R.string.tone_map_reinhard_desc)
            )
        )
        SingleChoiceDialog(
            title = stringResource(R.string.stats_tone_mapping),
            options = options,
            selectedIndex = algorithms.indexOf(settings.toneMapping).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateToneMapping(algorithms[idx])
                showToneMappingDialog = false
            },
            onDismiss = { showToneMappingDialog = false }
        )
    }
}

@Composable
private fun PreferenceGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GyroColors.PrimaryLight,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = GyroColors.Surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun PreferenceSwitchItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GyroColors.TextPrimary)
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, fontSize = 12.sp, color = GyroColors.TextSecondary)
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
private fun PreferenceClickItem(
    title: String,
    value: String? = null,
    description: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = GyroColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                fontSize = 13.sp,
                color = GyroColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
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
private fun PreferenceInfoItem(
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = GyroColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            color = GyroColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
        )
    }
}

private data class DialogOption(
    val title: String,
    val description: String? = null,
    /** False renders the row greyed out and ignores taps (unavailable on this build). */
    val enabled: Boolean = true
)

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<DialogOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GyroColors.SurfaceVariant,
        title = {
            Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = GyroColors.TextPrimary)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.enabled) { onSelect(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = { onSelect(index) },
                            enabled = option.enabled,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = GyroColors.Primary,
                                unselectedColor = GyroColors.TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = option.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (option.enabled) {
                                    GyroColors.TextPrimary
                                } else {
                                    GyroColors.TextSecondary
                                }
                            )
                            if (option.description != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = option.description,
                                    fontSize = 12.sp,
                                    color = GyroColors.TextSecondary
                                )
                            }
                        }
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
