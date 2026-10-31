package dev.gyro.player

import android.app.PictureInPictureParams
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

class MainActivity : ComponentActivity() {

    private lateinit var playerEngine: GyroPlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        // Seamlessly enter Picture-in-Picture if playing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playerEngine.isPlaying.value) {
            val dims = playerEngine.videoDimensions.value
            val ratio = if (dims.width > 0 && dims.height > 0) {
                val clamped = dims.aspectRatio.coerceIn(0.42f, 2.38f)
                val num = (clamped * 100).toInt()
                Rational(num, 100)
            } else {
                Rational(16, 9)
            }

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
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
