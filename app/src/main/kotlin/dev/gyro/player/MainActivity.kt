package dev.gyro.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.player.navigation.GyroNavDisplay
import dev.gyro.player.navigation.rememberGyroAppState
import kotlinx.coroutines.flow.MutableStateFlow

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
        // 应用 UI 恒为深色，系统栏图标固定用浅色；不能跟随系统深浅模式，
        // 否则浅色系统模式下会给出深色图标，压在深色背景上看起来像“状态栏是黑的”。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

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
            val sessionInfo by viewModel.sessionInfo.collectAsStateWithLifecycle()

            val appState = rememberGyroAppState(
                initialSessionActive = sessionInfo.isActive,
                initialActiveUri = sessionInfo.activeUri,
            )
            val intentUri by incomingIntentUri.collectAsStateWithLifecycle()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            var wasSessionActive by remember { mutableStateOf(sessionInfo.isActive) }
            LaunchedEffect(sessionInfo.isActive) {
                if (wasSessionActive && !sessionInfo.isActive) {
                    appState.removePlayerScreens()
                }
                wasSessionActive = sessionInfo.isActive
            }

            // Background playback posts a media notification via Media3; without POST_NOTIFICATIONS
            // (Android 13+) the notification is suppressed and the foreground service never calls
            // startForeground, crashing with ForegroundServiceDidNotStartInTimeException.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LaunchedEffect(intentUri) {
                intentUri?.let { uri ->
                    viewModel.onOpenExternalUri(uri)
                    appState.handleIncomingUri(uri)
                    incomingIntentUri.value = null
                }
            }

            // Restoration guard only: a session that is active while no Player destination exists
            // (e.g. restored state that no longer shows the player) must not outlive its UI. Runtime
            // exits are detected by the player entry's disposal in GyroNavDisplay, which happens after
            // the pop transition instead of on its first frame.
            LaunchedEffect(Unit) {
                if (viewModel.sessionInfo.value.isActive && !appState.isPlayerOnStack) {
                    viewModel.onPlayerDestinationRemoved()
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = GyroColors.Background
            ) {
                GyroNavDisplay(
                    appState = appState,
                    viewModel = viewModel,
                    onFinish = { finish() },
                    onPlayerExited = {
                        viewModel.onPlayerDestinationRemoved()
                        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        incomingIntentUri.value = null
                    },
                    modifier = Modifier.fillMaxSize()
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

    override fun onStop() {
        super.onStop()
        viewModel.handleAppStop(isInPictureInPictureMode)
    }
}
