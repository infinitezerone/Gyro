package dev.gyro.player.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import dev.gyro.core.navigation.Screen
import kotlinx.coroutines.CoroutineScope

/**
 * Creates and remembers a [GyroAppState] UI state holder.
 *
 * Note: [initialSessionActive] and [initialActiveUri] serve solely as initial state seeds
 * on the very first composition or process resurrection to restore the backstack to
 * `[Screen.Home, Screen.Player(initialActiveUri)]`. They are intentionally captured once inside
 * `remember` and do NOT reactively drive backstack mutations during runtime.
 *
 * @param initialSessionActive Initial playback session presence on app launch/restoration.
 * @param initialActiveUri Active media URI to restore directly to [Screen.Player] if session is active.
 * @param coroutineScope Scope for launching navigation-related coroutines.
 */
@Composable
fun rememberGyroAppState(
    initialSessionActive: Boolean = false,
    initialActiveUri: Uri? = null,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): GyroAppState {
    // Determine the initial backstack keys ONCE at initial composition / restoration.
    // If a background playback session is already active with a valid URI, restore to [Home, Player(activeUri)].
    // Otherwise, start clean on [Home].
    val initialKeys = remember {
        if (initialSessionActive && initialActiveUri != null) {
            arrayOf(Screen.Home, Screen.Player(initialActiveUri))
        } else {
            arrayOf(Screen.Home)
        }
    }

    val backStack = rememberNavBackStack(*initialKeys)

    return remember(backStack, coroutineScope) {
        GyroAppState(backStack = backStack, coroutineScope = coroutineScope)
    }
}

/**
 * UI State Holder for Gyro navigation adhering to Now in Android architecture guidelines.
 * Encapsulates the Navigation 3 back stack and exposes semantic navigation operations with single-top
 * and synthetic backstack guarantees.
 */
@Stable
class GyroAppState(
    val backStack: NavBackStack<NavKey>,
    val coroutineScope: CoroutineScope,
) {
    val currentScreen: Screen?
        get() = backStack.lastOrNull() as? Screen

    val isPlayerOnStack: Boolean
        get() = backStack.any { it is Screen.Player }

    /**
     * Navigates to the player screen.
     * Enforces SingleTop semantics: if the top of the stack is already a [Screen.Player],
     * replaces its entry in place instead of stacking duplicates.
     */
    fun navigateToPlayer(uri: Uri) {
        val top = backStack.lastOrNull()
        if (top is Screen.Player) {
            if (top.uri != uri) {
                backStack[backStack.lastIndex] = Screen.Player(uri)
            }
        } else {
            backStack.add(Screen.Player(uri))
        }
    }

    fun navigateToSettings() {
        backStack.add(Screen.Settings)
    }

    fun navigateToFolderManagement() {
        backStack.add(Screen.FolderManagement)
    }

    fun navigateToMpvConfig() {
        backStack.add(Screen.MpvConfigEditor)
    }

    fun navigateToGestureSettings() {
        backStack.add(Screen.GestureSettings)
    }

    fun navigateToVideoRenderingSettings() {
        backStack.add(Screen.VideoRenderingSettings)
    }

    /**
     * Handles external intent URIs using single-top and synthetic backstack principles.
     * - If the player is already showing at the top with the same URI, avoids redundant work.
     * - If the player is already showing with a different URI, replaces the URI in place (single-top).
     * - If the player is not at the top (e.g. user was on Home, Settings, or a sub-editor),
     *   rebuilds the back stack into the canonical synthetic backstack [Screen.Home, Screen.Player(uri)].
     *   This guarantees a clean single-level back path to Home, preventing orphaned playback sessions,
     *   duplicate player instances, or unexpected back-navigation into stale sub-settings.
     */
    fun handleIncomingUri(uri: Uri) {
        val top = backStack.lastOrNull()
        if (top is Screen.Player) {
            if (top.uri != uri) {
                backStack[backStack.lastIndex] = Screen.Player(uri)
            }
        } else {
            backStack.clear()
            backStack.add(Screen.Home)
            backStack.add(Screen.Player(uri))
        }
    }

    /**
     * Pops the top destination from the back stack.
     * Returns the popped [NavKey] if the stack had more than 1 item, or null if at root.
     */
    fun popBack(): NavKey? {
        return if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            null
        }
    }

    /**
     * Removes any active [Screen.Player] entries from the back stack, ensuring [Screen.Home]
     * remains if removing player entries empties the stack.
     */
    fun removePlayerScreens() {
        backStack.removeAll { it is Screen.Player }
        if (backStack.isEmpty()) {
            backStack.add(Screen.Home)
        }
    }
}
