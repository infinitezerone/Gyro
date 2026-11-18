package dev.gyro.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.gyro.core.designsystem.theme.GyroColors
import dev.gyro.core.navigation.Screen
import dev.gyro.player.navigation.GyroNavDisplay
import dev.gyro.player.navigation.rememberGyroAppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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
            val sessionInfo by viewModel.sessionInfo.collectAsStateWithLifecycle()

            val appState = rememberGyroAppState(
                initialSessionActive = sessionInfo.isActive,
                initialActiveUri = sessionInfo.activeUri,
            )
            val intentUri by incomingIntentUri.collectAsStateWithLifecycle()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            var wasSessionActive by remember { mutableStateOf(sessionInfo.isActive) }
            LaunchedEffect(sessionInfo.isActive) {
                if (wasSessionActive && !sessionInfo.isActive && appState.backStack.lastOrNull() is Screen.Player) {
                    appState.popBack()
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

            // Leaving the player is a navigation fact: a Player entry that disappears from the back
            // stack ends the playback session (covers top-bar back, predictive back, system back, and gestures).
            // Initializing playerOnStack with (sessionActive || isPlayerOnStack) ensures that if Activity
            // was restored while a background session was running, any absence of Player safely ends the session.
            LaunchedEffect(appState) {
                var playerOnStack = viewModel.sessionInfo.value.isActive || appState.isPlayerOnStack
                snapshotFlow { appState.isPlayerOnStack }
                    .distinctUntilChanged()
                    .collect { present ->
                        if (playerOnStack && !present) {
                            viewModel.onPlayerDestinationRemoved()
                        }
                        playerOnStack = present
                    }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = GyroColors.Background
            ) {
                GyroNavDisplay(
                    appState = appState,
                    viewModel = viewModel,
                    onPlayerExit = {
                        viewModel.onPlayerDestinationRemoved()
                        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        incomingIntentUri.value = null
                    },
                    onFinish = { finish() },
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
