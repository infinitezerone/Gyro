package dev.gyro.player

import android.content.pm.ActivityInfo
import android.app.PictureInPictureUiState
import android.content.res.Configuration
import android.view.MotionEvent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.feature.player.session.PlaybackSessionOrchestrator
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.navigation.Screen
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.player.service.PlaybackService
import dev.gyro.feature.home.HomeScreen
import dev.gyro.feature.home.HomeViewModel
import dev.gyro.feature.player.PlayerScreen
import dev.gyro.feature.player.PlayerViewModel
import dev.gyro.feature.settings.SettingsScreen
import dev.gyro.feature.settings.SettingsViewModel
import dev.gyro.feature.settings.folders.FolderManagementScreen
import dev.gyro.feature.settings.folders.FolderManagementViewModel
import dev.gyro.feature.settings.gestures.GestureSettingsScreen
import dev.gyro.feature.settings.mpv.MpvConfigEditorScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

// Material 3 navigation motion specs: fixed-duration tweens instead of springs — a spring
// settles asymptotically, so its tail keeps the screen sub-pixel drifting after it looks done.
private val navSpatialSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val navSpatialFloatSpec = tween<Float>(300, easing = FastOutSlowInEasing)
private val navEffectsSpec = tween<Float>(200, easing = LinearOutSlowInEasing)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerEngine: GyroPlayerEngine
    @Inject lateinit var orchestrator: PlaybackSessionOrchestrator
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var mpvConfigRepository: MpvConfigRepository
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private val incomingIntentUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extend completely into display cutout (camera hole) in both portrait and landscape
        window.attributes.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

        // The engine takes configuration as values, not repositories. The first emission of the
        // DataStore flow is the real persisted value; reading a StateFlow here would have handed
        // mpv the defaults while the disk was still loading.
        lifecycleScope.launch {
            val settings = settingsRepository.settings.first()
            playerEngine.configure(
                userOptions = mpvConfigRepository.getParsedOptions(),
                settings = settings,
            )
            handleIntent(intent)
            settingsRepository.settings.collect { updatedSettings ->
                playerEngine.updateSettings(settings = updatedSettings)
            }
        }

        setContent {
            val backStack = rememberNavBackStack(Screen.Home)
            val intentUri by incomingIntentUri.collectAsState()
            val settings by settingsViewModel.settings.collectAsState()

            LaunchedEffect(intentUri) {
                intentUri?.let { uri ->
                    orchestrator.setSingleAndPlay(uri, uri.lastPathSegment ?: "Video")
                    val top = backStack.lastOrNull()
                    if (top is Screen.Player) {
                        if (top.uri == uri) return@LaunchedEffect
                        backStack.removeAt(backStack.lastIndex)
                    }
                    playerViewModel.notifyScreenEntry()
                    backStack.add(Screen.Player(uri))
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = GyroColors.Background
            ) {
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator()
                    ),
                    onBack = {
                        if (backStack.size > 1) {
                            val popped = backStack.removeAt(backStack.lastIndex)
                            if (popped is Screen.Player) {
                                playerEngine.pause()
                                playerEngine.abandonAudioFocus()
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
                                    orchestrator.setPlaylistAndPlay(currentList, item)
                                    playerViewModel.notifyScreenEntry()
                                    val itemAndroidUri = item.uriString.toUri()
                                    backStack.add(Screen.Player(itemAndroidUri))
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
                                viewModel = playerViewModel,
                                onBack = {
                                    playerViewModel.saveCurrentProgress()
                                    playerEngine.pause()
                                    playerEngine.abandonAudioFocus()
                                    if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    }
                                    incomingIntentUri.value = null
                                    backStack.removeAt(backStack.lastIndex)
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
                                    backStack.removeAt(backStack.lastIndex)
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
                                viewModel = hiltViewModel(),
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
                                    homeViewModel.refreshVideos()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.MpvConfigEditor> {
                            MpvConfigEditorScreen(
                                onBack = {
                                    lifecycleScope.launch {
                                        playerEngine.updateSettings(
                                            userOptions = mpvConfigRepository.getParsedOptions()
                                        )
                                    }
                                    backStack.removeAt(backStack.lastIndex)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.GestureSettings> {
                            GestureSettingsScreen(
                                onBack = {
                                    backStack.removeAt(backStack.lastIndex)
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
        val uri: Uri? = when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java)
                list?.firstOrNull() ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> {
                intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
        }

        Log.i("MainActivity", "handleIntent: action=${intent.action}, uri=$uri")
        uri?.let {
            incomingIntentUri.value = it
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground: dismiss the background audio notification service
        // so it does not stay pinned in the status bar while the user is actively viewing the video.
        PlaybackService.dismiss(this)
    }

    /**
     * Set when the system moves the activity into PiP and consulted by [onStop]: after a PiP
     * stop there is no [onResume] in between, so the flag surviving to [onStop] means the user
     * dismissed the PiP window. A fullscreen return always passes through [onResume], which
     * clears it.
     */
    private var wasInPipMode = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val dm = resources.displayMetrics
            val bottomNavZone = dm.heightPixels - (48 * dm.density)
            if (ev.rawY >= bottomNavZone) {
                playerViewModel.triggerForceHideControls()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playerViewModel.triggerForceHideControls()
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) {
            playerViewModel.triggerForceHideControls()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            wasInPipMode = true
        } else if (isFinishing) {
            // Dismissed PiP window via close button ('X')
            if (::playerEngine.isInitialized) {
                playerViewModel.saveCurrentProgress()
                playerEngine.pause()
                playerEngine.detachSurface()
            }
            PlaybackService.stop(this)
            wasInPipMode = false
        }
    }

    override fun onResume() {
        super.onResume()
        wasInPipMode = false
    }

    override fun onStop() {
        super.onStop()
        if (!::playerEngine.isInitialized) return

        // When the PiP window is dismissed or dragged away by user, onStop is called.
        // In Android, onStop is NEVER called while the PiP window is visible on screen.
        // Therefore, if wasInPipMode is true (or isInPictureInPictureMode is reported during teardown),
        // the user has explicitly closed the PiP window -> pause immediately!
        if (wasInPipMode || isInPictureInPictureMode) {
            wasInPipMode = false
            playerViewModel.saveCurrentProgress()
            playerEngine.pause()
            playerEngine.detachSurface()
            PlaybackService.stop(this)
            return
        }

        // Normal backgrounding (leaving app to home/recents without PiP, or screen turned off)
        if (playerEngine.isPlaying.value) {
            playerEngine.detachSurface()
            val bgAudioEnabled = settingsViewModel.settings.value.backgroundAudio
            if (bgAudioEnabled) {
                runCatching {
                    PlaybackService.start(this)
                }.onFailure { e ->
                    Log.w("MainActivity", "Failed to start PlaybackService from background", e)
                }
            } else {
                playerViewModel.saveCurrentProgress()
                playerEngine.pause()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations) return
        if (!PlaybackService.isRunning) {
            orchestrator.saveCurrentProgress()
            if (::playerEngine.isInitialized) {
                playerEngine.release()
            }
        }
    }
}
