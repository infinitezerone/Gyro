package dev.gyro.player

import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.gyro.engine.GyroPlayerEngine
import dev.gyro.player.ui.PlayerScreen
import dev.gyro.player.ui.buildPipParams

class MainActivity : ComponentActivity() {

    private lateinit var playerEngine: GyroPlayerEngine

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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                PlayerScreen(
                    engine = playerEngine,
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
            playerEngine.loadUri(it)
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
