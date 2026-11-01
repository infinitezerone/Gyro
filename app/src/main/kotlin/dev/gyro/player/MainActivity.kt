package dev.gyro.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.player.data.repository.PlaylistManager
import dev.gyro.player.ui.PlayerScreen
import dev.gyro.player.ui.buildPipParams
import dev.gyro.player.ui.home.HomeScreen
import dev.gyro.player.ui.home.HomeViewModel
import dev.gyro.player.ui.navigation.Screen
import dev.gyro.player.ui.settings.SettingsScreen
import dev.gyro.player.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var playerEngine: GyroPlayerEngine
    private val playlistManager = PlaylistManager()
    private val homeViewModel: HomeViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
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

        handleIntent(intent)

        setContent {
            val backStack = rememberNavBackStack(Screen.Home)
            val intentUri by incomingIntentUri.collectAsState()
            val settings by settingsViewModel.settings.collectAsState()
            val historyRepository = homeViewModel.historyRepository

            LaunchedEffect(intentUri) {
                intentUri?.let { uri ->
                    playlistManager.setSingle(uri, uri.lastPathSegment ?: "Video")
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
                color = Color.Black
            ) {
                NavDisplay(
                    backStack = backStack,
                    onBack = {
                        if (backStack.size > 1) {
                            val popped = backStack.removeLast()
                            if (popped is Screen.Player) {
                                playerEngine.pause()
                                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                incomingIntentUri.value = null
                            }
                        } else {
                            finish()
                        }
                    },
                    entryProvider = entryProvider {
                        entry<Screen.Home> {
                            HomeScreen(
                                viewModel = homeViewModel,
                                onPlayVideo = { item, currentList ->
                                    val idx = currentList.indexOfFirst { it.uri == item.uri }
                                    playlistManager.setPlaylist(currentList, if (idx >= 0) idx else 0)
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
                                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
            val dims = playerEngine.videoDimensions.value
            val ratio = if (dims.width > 0 && dims.height > 0) {
                val clamped = dims.aspectRatio.coerceIn(0.42f, 2.38f)
                val num = (clamped * 1000).toInt()
                Rational(num, 1000)
            } else {
                Rational(16, 9)
            }

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

    override fun onDestroy() {
        super.onDestroy()
        if (::playerEngine.isInitialized) {
            playerEngine.release()
        }
    }
}
