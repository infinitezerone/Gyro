package dev.gyro.player.ui.home

import android.Manifest
import android.content.Context
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
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
import dev.gyro.player.R
import dev.gyro.player.data.model.VideoMediaItem
import dev.gyro.player.data.repository.PlaybackProgress

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
                uri = it,
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
        containerColor = Color(0xFF0F1117),
        topBar = {
            if (uiState.isSearching) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF161A23)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onToggleSearch(false) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_close_search), tint = Color.White)
                        }
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(stringResource(R.string.home_search_hint), color = Color.White.copy(alpha = 0.5f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            singleLine = true,
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear), tint = Color.White.copy(alpha = 0.7f))
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
                                color = Color.White
                            )
                            Text(
                                text = if (uiState.hasPermission) {
                                    stringResource(R.string.home_video_count, uiState.rawVideos.size)
                                } else {
                                    stringResource(R.string.home_storage_unconnected)
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF141822)
                    ),
                    actions = {
                        IconButton(onClick = { onToggleSearch(true) }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search), tint = Color.White)
                        }
                        IconButton(onClick = onPickFolder) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.action_add_folder), tint = Color.White)
                        }
                        IconButton(onClick = onOpenFile) {
                            Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.action_open_file), tint = Color.White)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = Color.White)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title), tint = Color.White)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenFile,
                containerColor = Color(0xFF2196F3),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Pick Video File")
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
                    // 权限未授予提示态
                    PermissionCard(
                        onRequestPermission = onRequestPermission,
                        onOpenFile = onOpenFile,
                        onPickFolder = onPickFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .align(Alignment.Center)
                    )
                }
                uiState.isLoading && uiState.rawVideos.isEmpty() -> {
                    // 加载中
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF2196F3)
                    )
                }
                uiState.filteredVideos.isEmpty() -> {
                    // 空数据态
                    EmptyVideosCard(
                        isSearching = uiState.isSearching,
                        onOpenFile = onOpenFile,
                        onPickFolder = onPickFolder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .align(Alignment.Center)
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 文件夹分类 Chips
                        if (uiState.folders.isNotEmpty() && !uiState.isSearching) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = uiState.selectedFolder == null,
                                    onClick = { onSelectFolder(null) },
                                    label = { Text(stringResource(R.string.home_folder_all_count, uiState.rawVideos.size)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF2196F3),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E222D),
                                        labelColor = Color.White.copy(alpha = 0.7f)
                                    )
                                )
                                uiState.folders.forEach { folder ->
                                    val count = uiState.rawVideos.count { it.folderName == folder }
                                    FilterChip(
                                        selected = uiState.selectedFolder == folder,
                                        onClick = { onSelectFolder(folder) },
                                        label = { Text(stringResource(R.string.home_folder_item_count, folder, count)) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF2196F3),
                                            selectedLabelColor = Color.White,
                                            containerColor = Color(0xFF1E222D),
                                            labelColor = Color.White.copy(alpha = 0.7f)
                                        )
                                    )
                                }

                                InputChip(
                                    selected = false,
                                    onClick = onPickFolder,
                                    label = { Text(stringResource(R.string.action_add_folder)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Color(0xFF64B5F6),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = InputChipDefaults.inputChipColors(
                                        containerColor = Color(0xFF192535),
                                        labelColor = Color(0xFF64B5F6)
                                    )
                                )
                            }
                        }

                        // 视频列表
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = uiState.filteredVideos,
                                key = { it.id }
                            ) { item ->
                                val progress = uiState.playbackMap[item.uri.toString()]
                                VideoListItemCard(
                                    item = item,
                                    progress = progress,
                                    onClick = { onPlayVideo(item, uiState.filteredVideos) }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(72.dp)) // Avoid FAB overlap
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoListItemCard(
    item: VideoMediaItem,
    progress: PlaybackProgress? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF171B24),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面图 (16:9 比例)
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                VideoThumbnail(
                    item = item,
                    modifier = Modifier.fillMaxSize()
                )

                // 时长角标
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = item.formattedDuration,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                // 播放断点进度条
                if (progress != null && progress.positionMs > 2000L && progress.durationMs > 0L) {
                    LinearProgressIndicator(
                        progress = { progress.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = Color(0xFF2196F3),
                        trackColor = Color.White.copy(alpha = 0.25f),
                        drawStopIndicator = {}
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 视频元信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 分辨率徽章
                    item.resolutionBadge?.let { badge ->
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = Color(0xFF1E88E5).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64B5F6),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // 文件大小
                    Text(
                        text = item.formattedSize,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )

                    // 所属目录
                    Text(
                        text = "·",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    Text(
                        text = item.folderName,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionCard(
    onRequestPermission: () -> Unit,
    onOpenFile: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2B),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = Color(0xFF2196F3),
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.home_permission_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_permission_desc),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text(stringResource(R.string.action_grant_permission))
                }
                Button(
                    onClick = onPickFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3545))
                ) {
                    Text(stringResource(R.string.action_add_folder))
                }
                Button(
                    onClick = onOpenFile,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3545))
                ) {
                    Text(stringResource(R.string.action_open_file))
                }
            }
        }
    }
}

@Composable
fun EmptyVideosCard(
    isSearching: Boolean,
    onOpenFile: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1A1F2B),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isSearching) {
                    stringResource(R.string.home_empty_search_title)
                } else {
                    stringResource(R.string.home_empty_videos_title)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isSearching) {
                    stringResource(R.string.home_empty_search_desc)
                } else {
                    stringResource(R.string.home_empty_videos_desc)
                },
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
            if (!isSearching) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPickFolder,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text(stringResource(R.string.action_add_folder))
                    }
                    Button(
                        onClick = onOpenFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E3545))
                    ) {
                        Text(stringResource(R.string.action_open_file))
                    }
                }
            }
        }
    }
}
