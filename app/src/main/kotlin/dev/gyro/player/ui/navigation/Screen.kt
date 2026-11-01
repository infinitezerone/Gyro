package dev.gyro.player.ui.navigation

import android.net.Uri
import androidx.navigation3.runtime.NavKey

/**
 * Gyro 全局类型安全页面路由定义 (Navigation 3)
 */
sealed interface Screen : NavKey {
    data object Home : Screen
    data class Player(val uri: Uri) : Screen
    data object Settings : Screen
}
