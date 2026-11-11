package dev.gyro.player

import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.navigation.Screen
import dev.gyro.feature.home.HomeScreen
import dev.gyro.feature.player.PlayerScreen
import dev.gyro.feature.player.PlayerViewModel
import dev.gyro.feature.settings.SettingsScreen
import dev.gyro.feature.settings.folders.FolderManagementScreen
import dev.gyro.feature.settings.gestures.GestureSettingsScreen
import dev.gyro.feature.settings.mpv.MpvConfigEditorScreen
import dev.gyro.player.service.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow

private val navSpatialSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val navSpatialFloatSpec = tween<Float>(300, easing = FastOutSlowInEasing)
private val navEffectsSpec = tween<Float>(200, easing = LinearOutSlowInEasing)

/**
 * Single-Activity host adhering to Now in Android architecture guidelines.
 * All feature logic, playback control, viewmodels, and playlist coordination are decentralized
 * into their respective screens.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModels()
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

        // Only consume the launching intent on a fresh start. On process-death restoration the back
        // stack is rebuilt from saved state, and getIntent() returns the original launching intent
        // again; replaying it here would push a duplicate Player onto the restored stack.
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            val backStack = rememberNavBackStack(Screen.Home)
            val intentUri by incomingIntentUri.collectAsState()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(intentUri) {
                intentUri?.let { uri ->
                    val top = backStack.lastOrNull()
                    if (top is Screen.Player && top.uri == uri) return@LaunchedEffect
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
                                if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                incomingIntentUri.value = null
                            }
                        } else {
                            finish()
                        }
                    },
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
                                viewModel = hiltViewModel(),
                                onPlayVideo = { item, currentList ->
                                    viewModel.onPlayVideo(item, currentList)
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
                            val playerViewModel: PlayerViewModel = hiltViewModel()
                            playerViewModel.initialize(playerScreen.uri)
                            PlayerScreen(
                                viewModel = playerViewModel,
                                currentUri = playerScreen.uri,
                                onBack = {
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
                                viewModel = hiltViewModel(),
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
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        entry<Screen.MpvConfigEditor> {
                            MpvConfigEditorScreen(
                                onBack = {
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
        PlaybackService.dismiss(this)
    }

    override fun onStop() {
        super.onStop()
        val shouldStartBgAudio = viewModel.handleAppStop(isInPictureInPictureMode)
        if (shouldStartBgAudio) {
            runCatching {
                PlaybackService.start(this)
            }.onFailure { e ->
                Log.w("MainActivity", "Failed to start PlaybackService from background", e)
            }
        } else {
            PlaybackService.stop(this)
        }
    }
}
