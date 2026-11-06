package dev.gyro.feature.settings.mpv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gyro.core.designsystem.R
import dev.gyro.core.designsystem.theme.GyroColors
import kotlinx.coroutines.launch

@Composable
fun MpvConfigEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MpvConfigEditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Listen to user message events
    LaunchedEffect(uiState.userMessageRes, uiState.errorMessage) {
        uiState.userMessageRes?.let { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
            viewModel.dismissMessage()
        }
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.dismissMessage()
        }
    }

    // Intercept back gesture if dirty
    BackHandler(enabled = uiState.isModified) {
        viewModel.showDiscardConfirmation(true)
    }

    MpvConfigEditorContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = {
            if (uiState.isModified) {
                viewModel.showDiscardConfirmation(true)
            } else {
                onBack()
            }
        },
        onContentChange = viewModel::updateContent,
        onSave = viewModel::saveConfig,
        onReset = { viewModel.showResetConfirmation(true) },
        onCopy = {
            clipboardManager.setText(AnnotatedString(uiState.content))
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.mpv_config_copied_toast))
            }
        },
        onHelp = { viewModel.showHelp(true) },
        onInsertPreset = viewModel::insertOption,
        onConfirmReset = viewModel::resetToDefault,
        onDismissResetDialog = { viewModel.showResetConfirmation(false) },
        onConfirmDiscard = {
            viewModel.showDiscardConfirmation(false)
            onBack()
        },
        onDismissDiscardDialog = { viewModel.showDiscardConfirmation(false) },
        onDismissHelpDialog = { viewModel.showHelp(false) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpvConfigEditorContent(
    uiState: MpvConfigEditorUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onCopy: () -> Unit,
    onHelp: () -> Unit,
    onInsertPreset: (String) -> Unit,
    onConfirmReset: () -> Unit,
    onDismissResetDialog: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscardDialog: () -> Unit,
    onDismissHelpDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = GyroColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.mpv_config_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = GyroColors.TextPrimary
                        )
                        if (uiState.isModified) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = CircleShape,
                                color = GyroColors.AccentOrange.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = stringResource(R.string.mpv_config_unsaved),
                                    color = GyroColors.AccentOrange,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.mpv_config_copy),
                            tint = GyroColors.TextPrimary
                        )
                    }
                    IconButton(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = stringResource(R.string.mpv_config_reset_default),
                            tint = GyroColors.TextPrimary
                        )
                    }
                    IconButton(onClick = onHelp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.mpv_config_help_title),
                            tint = GyroColors.TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GyroColors.TopBarBackground
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onSave,
                expanded = true,
                containerColor = if (uiState.isModified) GyroColors.Primary else GyroColors.SurfaceVariant,
                contentColor = if (uiState.isModified) Color.White else GyroColors.TextMuted,
                shape = CircleShape,
                icon = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.mpv_config_save)
                        )
                    }
                },
                text = {
                    Text(
                        text = stringResource(R.string.mpv_config_save),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Quick Presets Row (Pure floating style)
            Text(
                text = stringResource(R.string.mpv_config_quick_presets),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = GyroColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MPV_QUICK_PRESETS.forEach { preset ->
                    SuggestionChip(
                        onClick = { onInsertPreset(preset.snippet) },
                        label = {
                            Text(
                                text = preset.label,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = GyroColors.PrimaryLight
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = GyroColors.PrimaryLight
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = GyroColors.SurfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = GyroColors.PrimaryLight.copy(alpha = 0.25f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Monospace Editor Area (Pure floating UI: no opaque solid card block)
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = GyroColors.PrimaryLight)
                }
            } else {
                OutlinedTextField(
                    value = uiState.content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        color = GyroColors.TextPrimary
                    ),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.mpv_config_hint),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = GyroColors.TextMuted
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = GyroColors.Primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = GyroColors.SurfaceVariant,
                        cursorColor = GyroColors.PrimaryLight
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer statistics (lines & characters)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.mpv_config_stats_format,
                        uiState.lineCount,
                        uiState.charCount
                    ),
                    fontSize = 12.sp,
                    color = GyroColors.TextMuted
                )
            }
        }
    }

    // Reset Confirmation Dialog
    if (uiState.showResetDialog) {
        AlertDialog(
            onDismissRequest = onDismissResetDialog,
            containerColor = GyroColors.SurfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.mpv_config_reset_dialog_title),
                    color = GyroColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mpv_config_reset_dialog_desc),
                    color = GyroColors.TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmReset) {
                    Text(
                        text = stringResource(R.string.action_reset),
                        color = GyroColors.Error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResetDialog) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = GyroColors.TextSecondary
                    )
                }
            }
        )
    }

    // Discard Changes Confirmation Dialog
    if (uiState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = onDismissDiscardDialog,
            containerColor = GyroColors.SurfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.mpv_config_discard_dialog_title),
                    color = GyroColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mpv_config_discard_dialog_desc),
                    color = GyroColors.TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) {
                    Text(
                        text = stringResource(R.string.mpv_config_action_discard),
                        color = GyroColors.AccentOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscardDialog) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = GyroColors.TextSecondary
                    )
                }
            }
        )
    }

    // Configuration Help Dialog
    if (uiState.showHelpDialog) {
        AlertDialog(
            onDismissRequest = onDismissHelpDialog,
            containerColor = GyroColors.SurfaceVariant,
            title = {
                Text(
                    text = stringResource(R.string.mpv_config_help_title),
                    color = GyroColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.mpv_config_help_desc),
                        color = GyroColors.TextSecondary,
                        fontSize = 13.5.sp
                    )
                    Text(
                        text = stringResource(R.string.mpv_config_hint),
                        color = GyroColors.PrimaryLight,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(
                                color = GyroColors.Background.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissHelpDialog) {
                    Text(
                        text = stringResource(R.string.action_confirm),
                        color = GyroColors.PrimaryLight
                    )
                }
            }
        )
    }
}
