package dev.gyro.feature.home

import dev.gyro.core.designsystem.R

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayVideo: (VideoMediaItem, List<VideoMediaItem>) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // SAF File Picker
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val singleItem = VideoMediaItem(
                id = System.currentTimeMillis(),
                uriString = it.toString(),
                displayName = it.lastPathSegment ?: "Video",
                durationMs = 0L,
                sizeBytes = 0L,
                width = 0,
                height = 0,
                dateModified = 0L,
                folderName = ""
            )
            onPlayVideo(singleItem, listOf(singleItem))
        }
    }

    // SAF Directory Picker (OpenDocumentTree)
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { viewModel.addCustomFolder(it) }
    }

    // Permission Launcher
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            requiredPermission
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    HomeContent(
        uiState = uiState,
        onPlayVideo = onPlayVideo,
        onOpenFile = { openDocumentLauncher.launch(arrayOf("video/*")) },
        onPickFolder = { openFolderLauncher.launch(null) },
        onOpenSettings = onOpenSettings,
        onRequestPermission = { permissionLauncher.launch(requiredPermission) },
        onRefresh = { viewModel.refreshVideos() },
        onSelectFolder = { viewModel.selectFolder(it) },
        onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
        onToggleSearch = { viewModel.toggleSearch(it) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    onPlayVideo: (VideoMediaItem, List<VideoMediaItem>) -> Unit,
    onOpenFile: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelectFolder: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GyroColors.Background,
        topBar = {
            if (uiState.isSearching) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = GyroColors.SurfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onToggleSearch(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_close_search), tint = GyroColors.TextPrimary)
                        }
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(stringResource(R.string.home_search_hint), color = GyroColors.TextSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = GyroColors.TextPrimary,
                                unfocusedTextColor = GyroColors.TextPrimary,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear), tint = GyroColors.TextSecondary)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.home_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = GyroColors.TextPrimary
                            )
                            Text(
                                text = if (uiState.hasPermission) {
                                    stringResource(R.string.home_video_count, uiState.rawVideos.size)
                                } else {
                                    stringResource(R.string.home_storage_unconnected)
                                },
                                fontSize = 12.sp,
                                color = GyroColors.TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GyroColors.TopBarBackground
                    ),
                    actions = {
                        IconButton(onClick = { onToggleSearch(true) }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onPickFolder) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.action_add_folder), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onOpenFile) {
                            Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.action_open_file), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings), tint = GyroColors.TextPrimary)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenFile,
                containerColor = GyroColors.Primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.action_open_file))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !uiState.hasPermission -> {
                    PermissionRequiredView(
                        onRequestPermission = onRequestPermission,
                        onOpenFile = onOpenFile,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.isLoading && uiState.rawVideos.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = GyroColors.Primary
                    )
                }
                uiState.rawVideos.isEmpty() -> {
                    EmptyVideosView(
                        onOpenFile = onOpenFile,
                        onPickFolder = onPickFolder,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.filteredVideos.isEmpty() -> {
                    EmptySearchView(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 文件夹分类筛选横条
                        if (uiState.folders.size > 1 && !uiState.isSearching) {
                            FolderFilterBar(
                                folders = uiState.folders,
                                selectedFolder = uiState.selectedFolder,
                                allCount = uiState.rawVideos.size,
                                rawVideos = uiState.rawVideos,
                                onSelectFolder = onSelectFolder
                            )
                        }

                        // 视频列表
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = uiState.filteredVideos,
                                key = { it.id }
                            ) { videoItem ->
                                val progress = uiState.playbackMap[videoItem.uriString]
                                VideoCard(
                                    item = videoItem,
                                    playbackProgress = progress,
                                    onClick = { onPlayVideo(videoItem, uiState.filteredVideos) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderFilterBar(
    folders: List<String>,
    selectedFolder: String?,
    allCount: Int,
    rawVideos: List<VideoMediaItem>,
    onSelectFolder: (String?) -> Unit
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selectedFolder == null,
            onClick = { onSelectFolder(null) },
            label = {
                Text(
                    stringResource(R.string.home_folder_all_count, allCount),
                    fontSize = 13.sp
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = GyroColors.Primary,
                selectedLabelColor = Color.White,
                containerColor = GyroColors.Surface,
                labelColor = GyroColors.TextSecondary
            ),
            border = null,
            shape = RoundedCornerShape(20.dp)
        )

        folders.forEach { folderName ->
            val count = rawVideos.count { it.folderName == folderName }
            FilterChip(
                selected = selectedFolder == folderName,
                onClick = { onSelectFolder(folderName) },
                label = {
                    Text(
                        stringResource(R.string.home_folder_item_count, folderName, count),
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (selectedFolder == folderName) Color.White else GyroColors.PrimaryLight
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GyroColors.Primary,
                    selectedLabelColor = Color.White,
                    containerColor = GyroColors.Surface,
                    labelColor = GyroColors.TextSecondary
                ),
                border = null,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
private fun VideoCard(
    item: VideoMediaItem,
    playbackProgress: PlaybackProgress?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = GyroColors.Surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 视频缩略图预览
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222634))
            ) {
                VideoThumbnail(
                    uri = Uri.parse(item.uriString),
                    modifier = Modifier.fillMaxSize()
                )

                // 底部时长气泡
                if (item.durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = item.formattedDuration,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }

                // 底部轻量播放进度条 (3dp 高度蓝条)
                if (playbackProgress != null && playbackProgress.positionMs > 2000L && item.durationMs > 0L) {
                    LinearProgressIndicator(
                        progress = { playbackProgress.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = GyroColors.PrimaryLight,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 视频详情信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GyroColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item.folderName.isNotBlank()) {
                        Text(
                            text = item.folderName,
                            fontSize = 12.sp,
                            color = GyroColors.PrimaryLight,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "·",
                            fontSize = 12.sp,
                            color = GyroColors.TextMuted
                        )
                    }

                    Text(
                        text = item.formattedSize,
                        fontSize = 12.sp,
                        color = GyroColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequiredView(
    onRequestPermission: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = GyroColors.Primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_permission_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = GyroColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_permission_desc),
            style = MaterialTheme.typography.bodySmall,
            color = GyroColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = GyroColors.Primary)
            ) {
                Text(stringResource(R.string.action_grant_permission))
            }
            Button(
                onClick = onOpenFile,
                colors = ButtonDefaults.buttonColors(containerColor = GyroColors.Surface)
            ) {
                Text(stringResource(R.string.action_open_file))
            }
        }
    }
}

@Composable
private fun EmptyVideosView(
    onOpenFile: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = GyroColors.TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_empty_videos_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = GyroColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_empty_videos_desc),
            style = MaterialTheme.typography.bodySmall,
            color = GyroColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onOpenFile,
                colors = ButtonDefaults.buttonColors(containerColor = GyroColors.Primary)
            ) {
                Text(stringResource(R.string.action_open_file))
            }
            Button(
                onClick = onPickFolder,
                colors = ButtonDefaults.buttonColors(containerColor = GyroColors.Surface)
            ) {
                Text(stringResource(R.string.action_add_folder))
            }
        }
    }
}

@Composable
private fun EmptySearchView(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = GyroColors.TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_empty_search_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = GyroColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_empty_search_desc),
            style = MaterialTheme.typography.bodySmall,
            color = GyroColors.TextSecondary
        )
    }
}
