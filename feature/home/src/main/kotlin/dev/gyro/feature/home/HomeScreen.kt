package dev.gyro.feature.home

import dev.gyro.core.designsystem.R

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import dev.gyro.core.domain.model.VideoSortOrder
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.domain.model.MediaFeedState
import dev.gyro.core.model.PlaybackProgress
import dev.gyro.core.model.VideoMediaItem
import dev.gyro.core.designsystem.icon.GyroIcons

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayVideo: (VideoMediaItem, List<VideoMediaItem>) -> Unit,
    onOpenSingleUri: (Uri) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android Photo & Video Picker (PickVisualMedia)
    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let(onOpenSingleUri)
    }

    // SAF File Picker
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let(onOpenSingleUri)
    }

    // SAF Directory Picker (OpenDocumentTree)
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { viewModel.addCustomFolder(it) }
    }

    // Permission Launcher (supports Android 14+ partial media access via READ_MEDIA_VISUAL_USER_SELECTED)
    val requiredPermissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val isGranted = results.values.any { it }
        viewModel.onPermissionResult(isGranted)
    }

    LaunchedEffect(Unit) {
        val granted = requiredPermissions.any { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        viewModel.onPermissionResult(granted)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val deleteConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.refreshVideos()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeUiEvent.RequestDeleteConsent -> {
                    deleteConsentLauncher.launch(
                        IntentSenderRequest.Builder(event.intentSender).build()
                    )
                }
                is HomeUiEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    HomeContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onPlayVideo = onPlayVideo,
        onOpenFile = {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                pickVisualMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } else {
                openDocumentLauncher.launch(arrayOf("video/*"))
            }
        },
        onPickFolder = { openFolderLauncher.launch(null) },
        onOpenSettings = onOpenSettings,
        onRequestPermission = { permissionLauncher.launch(requiredPermissions) },
        onRefresh = { viewModel.refreshVideos() },
        onSelectFolder = { viewModel.selectFolder(it) },
        onSetSortOrder = { viewModel.setSortOrder(it) },
        onDeleteVideo = { viewModel.deleteVideo(it) },
        onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
        onToggleSearch = { viewModel.toggleSearch(it) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onPlayVideo: (VideoMediaItem, List<VideoMediaItem>) -> Unit,
    onOpenFile: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelectFolder: (String?) -> Unit,
    onSetSortOrder: (VideoSortOrder) -> Unit = {},
    onDeleteVideo: (VideoMediaItem) -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = GyroColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            Icon(GyroIcons.Back, contentDescription = stringResource(R.string.cd_close_search), tint = GyroColors.TextPrimary)
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
                                        Icon(GyroIcons.Close, contentDescription = stringResource(R.string.action_clear), tint = GyroColors.TextSecondary)
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
                                    stringResource(R.string.home_video_count, uiState.mediaFeed.rawVideoCount)
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
                            Icon(GyroIcons.Search, contentDescription = stringResource(R.string.action_search), tint = GyroColors.TextPrimary)
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(GyroIcons.Sort, contentDescription = stringResource(R.string.action_sort), tint = GyroColors.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                val sortOptions = listOf(
                                    VideoSortOrder.DATE_MODIFIED_DESC to "修改时间 (最新优先)",
                                    VideoSortOrder.DATE_MODIFIED_ASC to "修改时间 (最早优先)",
                                    VideoSortOrder.NAME_ASC to "视频名称 (A-Z)",
                                    VideoSortOrder.NAME_DESC to "视频名称 (Z-A)",
                                    VideoSortOrder.SIZE_DESC to "文件大小 (大到小)",
                                    VideoSortOrder.DURATION_DESC to "视频时长 (长到短)",
                                )
                                sortOptions.forEach { (order, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (uiState.sortOrder == order) {
                                                Icon(GyroIcons.Check, contentDescription = null, tint = GyroColors.Primary)
                                            }
                                        },
                                        onClick = {
                                            onSetSortOrder(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onPickFolder) {
                            Icon(GyroIcons.CreateNewFolder, contentDescription = stringResource(R.string.action_add_folder), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onOpenFile) {
                            Icon(GyroIcons.FolderOpen, contentDescription = stringResource(R.string.action_open_file), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(GyroIcons.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = GyroColors.TextPrimary)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(GyroIcons.Settings, contentDescription = stringResource(R.string.cd_settings), tint = GyroColors.TextPrimary)
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
                Icon(GyroIcons.FolderOpen, contentDescription = stringResource(R.string.action_open_file))
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
                else -> when (val feed = uiState.mediaFeed) {
                    // The only state allowed to show a spinner: nothing is known yet. An empty list
                    // here would be a claim about the library, which is what used to flash.
                    MediaFeedState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = GyroColors.Primary
                        )
                    }
                    is MediaFeedState.Ready -> when {
                        feed.rawVideos.isEmpty() -> {
                            EmptyVideosView(
                                onOpenFile = onOpenFile,
                                onPickFolder = onPickFolder,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        feed.filteredVideos.isEmpty() -> {
                            EmptySearchView(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        else -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 文件夹分类筛选横条
                                if (feed.folders.size > 1 && !uiState.isSearching) {
                                    FolderFilterBar(
                                        folders = feed.folders,
                                        selectedFolder = feed.effectiveSelectedFolder,
                                        allCount = feed.rawVideos.size,
                                        rawVideos = feed.rawVideos,
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
                                        items = feed.filteredVideos,
                                        key = { it.uriString }
                                    ) { videoItem ->
                                        val progress = feed.playbackMap[videoItem.uriString]
                                        VideoCard(
                                            item = videoItem,
                                            playbackProgress = progress,
                                            onClick = { onPlayVideo(videoItem, feed.filteredVideos) },
                                            onDelete = { onDeleteVideo(videoItem) }
                                        )
                                    }
                                }
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
    val folderCounts = remember(rawVideos) { rawVideos.groupingBy { it.folderName }.eachCount() }
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
            val count = folderCounts[folderName] ?: 0
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
                        imageVector = GyroIcons.Folder,
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
    onClick: () -> Unit,
    onDelete: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("删除视频") },
            text = { Text("确定要删除 \"${item.displayName}\" 吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

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
                val thumbnailUri = remember(item.uriString) { Uri.parse(item.uriString) }
                VideoThumbnail(
                    uri = thumbnailUri,
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
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.weight(1f, fill = false)
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

                    val badge = item.resolutionBadge
                    if (!badge.isNullOrBlank()) {
                        Text(
                            text = "·",
                            fontSize = 12.sp,
                            color = GyroColors.TextMuted
                        )
                        Text(
                            text = badge,
                            fontSize = 11.sp,
                            color = GyroColors.PrimaryLight,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 更多操作按钮
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = GyroIcons.MoreVert,
                        contentDescription = "更多操作",
                        tint = GyroColors.TextMuted
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                GyroIcons.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            showConfirmDialog = true
                        }
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
            imageVector = GyroIcons.Folder,
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
            imageVector = GyroIcons.VideoLibrary,
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
            imageVector = GyroIcons.Search,
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
