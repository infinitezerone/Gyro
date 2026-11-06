package dev.gyro.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import dev.gyro.core.data.repository.PlaylistManager
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.navigation.Screen
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.feature.home.HomeScreen
import dev.gyro.feature.home.HomeViewModel
import dev.gyro.feature.player.PlayerScreen
import dev.gyro.feature.player.buildPipParams
import dev.gyro.feature.settings.SettingsScreen
import dev.gyro.feature.settings.SettingsViewModel
import dev.gyro.feature.settings.folders.FolderManagementScreen
import dev.gyro.feature.settings.folders.FolderManagementViewModel
import dev.gyro.feature.settings.gestures.GestureSettingsScreen
import dev.gyro.feature.settings.mpv.MpvConfigEditorScreen
import kotlinx.coroutines.flow.MutableStateFlow

// Material 3 navigation motion specs: fixed-duration tweens instead of springs — a spring
// settles asymptotically, so its tail keeps the screen sub-pixel drifting after it looks done.
private val navSpatialSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val navSpatialFloatSpec = tween<Float>(300, easing = FastOutSlowInEasing)
private val navEffectsSpec = tween<Float>(200, easing = LinearOutSlowInEasing)

class MainActivity : ComponentActivity() {

    private lateinit var playerEngine: GyroPlayerEngine
    private val playlistManager = PlaylistManager()
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val folderViewModel: FolderManagementViewModel by viewModels()
    private val incomingIntentUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extend completely into display cutout (camera hole) in both portrait and landscape
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }

        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerEngine = GyroPlayerEngine(this).apply {
            ensureInitialized()
        }
        PlaybackService.activeEngine = playerEngine
        PlaybackService.activePlaylistManager = playlistManager

        handleIntent(intent)

        setContent {
            val backStack = rememberNavBackStack(Screen.Home)
            val intentUri by incomingIntentUri.collectAsState()
            val settings by settingsViewModel.settings.collectAsState()
            val historyRepository = homeViewModel.historyRepository

            LaunchedEffect(intentUri) {
                intentUri?.let { uri ->
                    playlistManager.setSingle(uri, uri.lastPathSegment ?: "Video")
                    // Opening another video from outside while a player page is already on top
                    // must replace it, not stack: every VIEW intent used to push one more player
                    // entry, so the back gesture walked back through every video ever opened.
                    val top = backStack.lastOrNull()
                    if (top is Screen.Player) {
                        if (top.uri == uri) return@LaunchedEffect
                        backStack.removeAt(backStack.lastIndex)
                    }
                    playerEngine.notifyScreenEntry()
                    backStack.add(Screen.Player(uri))
                    val startPos = if (settings.autoResume) {
                        val progress = historyRepository.getProgress(uri)
                        if (progress != null && !progress.isCompleted && progress.positionMs > 2000L) {
                            progress.positionMs
                        } else 0L
                    } else 0L
                    playerEngine.loadUri(uri, startPos)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = GyroColors.Background
            ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = {
                        if (backStack.size > 1) {
                            val popped = backStack.removeLast()
                            if (popped is Screen.Player) {
                                playerEngine.pause()
                                if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                incomingIntentUri.value = null
                            }
                        } else {
                            finish()
                        }
                    },
                    // Material 3 "Forward and Backward" navigation transitions (same family as
                    // BgmPlus): the incoming screen pushes in from the right edge with the outgoing
                    // one retreating to a quarter-width parallax, and popping plays the exact
                    // reverse. No scene-type branching: Nav3 hands these lambdas an internal
                    // scene wrapper, so every key/type comparison against Screen never matched.
                    transitionSpec = {
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = navSpatialSpec,
                        ) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> -fullWidth / 4 },
                                animationSpec = navSpatialSpec,
                            ) +
                            fadeOut(
                                targetAlpha = 0.0f,
                                animationSpec = navEffectsSpec,
                            )
                    },
                    popTransitionSpec = {
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> -fullWidth / 4 },
                            animationSpec = navSpatialSpec,
                        ) +
                            fadeIn(
                                initialAlpha = 0.0f,
                                animationSpec = navEffectsSpec,
                            ) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = navSpatialSpec,
                            )
                    },
                    predictivePopTransitionSpec = { swipeEdge ->
                        val isFromRight = swipeEdge == NavigationEvent.EDGE_RIGHT
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> if (isFromRight) fullWidth / 4 else -fullWidth / 4 },
                            animationSpec = navSpatialSpec,
                        ) +
                            scaleIn(
                                initialScale = 0.96f,
                                animationSpec = navSpatialFloatSpec,
                            ) +
                            fadeIn(animationSpec = navEffectsSpec) togetherWith
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> if (isFromRight) -fullWidth else fullWidth },
                                animationSpec = navSpatialSpec,
                            ) +
                            scaleOut(
                                targetScale = 0.90f,
                                animationSpec = navSpatialFloatSpec,
                            ) +
                            fadeOut(
                                animationSpec = navEffectsSpec,
                            )
                    },
                    entryProvider = entryProvider {
                        entry<Screen.Home> {
                            HomeScreen(
                                viewModel = homeViewModel,
                                onPlayVideo = { item, currentList ->
                                    val idx = currentList.indexOfFirst { it.uri == item.uri }
                                    playlistManager.setPlaylist(currentList, if (idx >= 0) idx else 0)
                                    playerEngine.notifyScreenEntry()
                                    backStack.add(Screen.Player(item.uri))
                                    val startPos = if (settings.autoResume) {
                                        val progress = historyRepository.getProgress(item.uri)
                                        if (progress != null && !progress.isCompleted && progress.positionMs > 2000L) {
                                            progress.positionMs
                                        } else 0L
                                    } else 0L
                                    playerEngine.loadUri(item.uri, startPos)
                                },
                                onOpenSettings = {
                                    backStack.add(Screen.Settings)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.Player> { playerScreen ->
                            PlayerScreen(
                                engine = playerEngine,
                                currentUri = playerScreen.uri,
                                settings = settings,
                                historyRepository = historyRepository,
                                playlistManager = playlistManager,
                                onBack = {
                                    playerEngine.pause()
                                    if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    }
                                    incomingIntentUri.value = null
                                    backStack.removeLast()
                                },
                                onOpenSettings = {
                                    backStack.add(Screen.Settings)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.Settings> {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = {
                                    backStack.removeLast()
                                },
                                onOpenFolderManagement = {
                                    backStack.add(Screen.FolderManagement)
                                },
                                onNavigateToMpvConfig = {
                                    backStack.add(Screen.MpvConfigEditor)
                                },
                                onNavigateToGestures = {
                                    backStack.add(Screen.GestureSettings)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.FolderManagement> {
                            FolderManagementScreen(
                                viewModel = folderViewModel,
                                onBack = {
                                    backStack.removeLast()
                                    homeViewModel.refreshVideos()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.MpvConfigEditor> {
                            MpvConfigEditorScreen(
                                onBack = {
                                    backStack.removeLast()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.GestureSettings> {
                            GestureSettingsScreen(
                                onBack = {
                                    backStack.removeLast()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val uri: android.net.Uri? = when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)
                }
                list?.firstOrNull() ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> {
                intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
        }

        android.util.Log.i("MainActivity", "handleIntent: action=${intent.action}, uri=$uri")
        uri?.let {
            incomingIntentUri.value = it
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // On Android 12+, autoEnterEnabled on PictureInPictureParams handles home gesture automatically.
        // For Android 8.0 - 11 (API 26-30), manually trigger enterPictureInPictureMode.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R && playerEngine.isPlaying.value) {
            val videoAspect = playerEngine.videoAspect.value
            val dims = playerEngine.videoDimensions.value
            val rawRatio = when {
                videoAspect > 0f -> videoAspect
                dims.width > 0 && dims.height > 0 -> dims.aspectRatio
                else -> 16f / 9f
            }.coerceIn(0.42f, 2.38f)
            val ratio = Rational((rawRatio * 1000).toInt(), 1000)

            val params = buildPipParams(
                context = this,
                isPlaying = true,
                aspectRatio = ratio,
                sourceRect = null,
                autoEnter = true
            )
            enterPictureInPictureMode(params)
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground: dismiss the background audio notification service
        // so it does not stay pinned in the status bar while the user is actively viewing the video.
        PlaybackService.dismiss(this)
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && ::playerEngine.isInitialized && playerEngine.isPlaying.value) {
            // Detach video surface for headless EGL_NO_SURFACE operation in background
            playerEngine.detachSurface()
            // Launch foreground service for background audio playback
            runCatching {
                PlaybackService.start(this)
            }.onFailure { e ->
                Log.w("MainActivity", "Failed to start PlaybackService from background", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations) return
        PlaybackService.stop(this)
        PlaybackService.activeEngine = null
        PlaybackService.activePlaylistManager = null
        if (::playerEngine.isInitialized) {
            playerEngine.release()
        }
    }
}
