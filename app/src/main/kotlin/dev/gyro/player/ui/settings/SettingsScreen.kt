package dev.gyro.player.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gyro.core.model.UserSettings
import dev.gyro.player.R

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenFolderManagement: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    SettingsContent(
        settings = settings,
        onBack = onBack,
        onOpenFolderManagement = onOpenFolderManagement,
        onUpdateAutoResume = { viewModel.setAutoResume(it) },
        onUpdateBgPlay = { viewModel.setBackgroundPlayMode(it) },
        onUpdateSeekStep = { viewModel.setSeekStepSeconds(it) },
        onUpdateHwdec = { viewModel.setDefaultHwdec(it) },
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
    onUpdateAutoResume: (Boolean) -> Unit,
    onUpdateBgPlay: (String) -> Unit,
    onUpdateSeekStep: (Int) -> Unit,
    onUpdateHwdec: (String) -> Unit,
    onUpdateSubAutoLoad: (Boolean) -> Unit,
    onUpdateGestures: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSeekStepDialog by remember { mutableStateOf(false) }
    var showBgPlayDialog by remember { mutableStateOf(false) }
    var showHwdecDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0F1117),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF141822)
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

                    PreferenceClickItem(
                        title = stringResource(R.string.settings_seek_step),
                        value = stringResource(R.string.settings_seek_step_val, settings.seekStepSeconds),
                        onClick = { showSeekStepDialog = true }
                    )

                    val bgPlayLabel = when (settings.backgroundPlayMode) {
                        "pip" -> stringResource(R.string.settings_bg_play_pip)
                        "audio_only" -> stringResource(R.string.settings_bg_play_audio)
                        else -> stringResource(R.string.settings_bg_play_off)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_bg_play),
                        value = bgPlayLabel,
                        onClick = { showBgPlayDialog = true }
                    )
                }
            }

            // 2. 解码与视频
            item {
                PreferenceGroup(title = stringResource(R.string.settings_group_decoder)) {
                    val hwdecLabel = when (settings.defaultHwdec) {
                        "mediacodec" -> stringResource(R.string.settings_hwdec_mediacodec)
                        "mediacodec-copy" -> stringResource(R.string.settings_hwdec_copy)
                        else -> stringResource(R.string.settings_hwdec_sw)
                    }
                    PreferenceClickItem(
                        title = stringResource(R.string.settings_default_hwdec),
                        value = hwdecLabel,
                        onClick = { showHwdecDialog = true }
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
                }
            }

            // 5. 存储与媒体库
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
            options = options.map { stringResource(R.string.settings_seek_step_val, it) },
            selectedIndex = options.indexOf(settings.seekStepSeconds).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateSeekStep(options[idx])
                showSeekStepDialog = false
            },
            onDismiss = { showSeekStepDialog = false }
        )
    }

    // 单选弹窗: 后台播放
    if (showBgPlayDialog) {
        val modes = listOf("pip", "audio_only", "off")
        val labels = listOf(
            stringResource(R.string.settings_bg_play_pip),
            stringResource(R.string.settings_bg_play_audio),
            stringResource(R.string.settings_bg_play_off)
        )
        SingleChoiceDialog(
            title = stringResource(R.string.settings_bg_play),
            options = labels,
            selectedIndex = modes.indexOf(settings.backgroundPlayMode).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateBgPlay(modes[idx])
                showBgPlayDialog = false
            },
            onDismiss = { showBgPlayDialog = false }
        )
    }

    // 单选弹窗: 默认解码器
    if (showHwdecDialog) {
        val hwdecModes = listOf("mediacodec", "mediacodec-copy", "")
        val labels = listOf(
            stringResource(R.string.settings_hwdec_mediacodec),
            stringResource(R.string.settings_hwdec_copy),
            stringResource(R.string.settings_hwdec_sw)
        )
        SingleChoiceDialog(
            title = stringResource(R.string.settings_default_hwdec),
            options = labels,
            selectedIndex = hwdecModes.indexOf(settings.defaultHwdec).coerceAtLeast(0),
            onSelect = { idx ->
                onUpdateHwdec(hwdecModes[idx])
                showHwdecDialog = false
            },
            onDismiss = { showHwdecDialog = false }
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
            color = Color(0xFF64B5F6),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171B24),
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
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2196F3)
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
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
        if (value != null) {
            Text(text = value, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Text(text = value, fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
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
        containerColor = Color(0xFF1E2330),
        title = {
            Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                                selectedColor = Color(0xFF2196F3),
                                unselectedColor = Color.White.copy(alpha = 0.6f)
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = option,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_back), color = Color(0xFF64B5F6))
            }
        }
    )
}
