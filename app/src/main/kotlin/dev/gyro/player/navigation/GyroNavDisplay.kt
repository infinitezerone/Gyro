package dev.gyro.player.navigation

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import dev.gyro.core.navigation.Screen
import dev.gyro.feature.home.HomeScreen
import dev.gyro.feature.player.PlayerScreen
import dev.gyro.feature.player.PlayerViewModel
import dev.gyro.feature.settings.SettingsScreen
import dev.gyro.feature.settings.folders.FolderManagementScreen
import dev.gyro.feature.settings.gestures.GestureSettingsScreen
import dev.gyro.feature.settings.mpv.MpvConfigEditorScreen
import dev.gyro.player.MainActivityViewModel

private val navSpatialSpec = tween<IntOffset>(300, easing = FastOutSlowInEasing)
private val navSpatialFloatSpec = tween<Float>(300, easing = FastOutSlowInEasing)
private val navEffectsSpec = tween<Float>(200, easing = LinearOutSlowInEasing)

/**
 * Top-level navigation display orchestrating screens and transitions via Navigation 3.
 */
@Composable
fun GyroNavDisplay(
    appState: GyroAppState,
    viewModel: MainActivityViewModel,
    onPlayerExit: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = appState.backStack,
        modifier = modifier,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        onBack = {
            val popped = appState.popBack()
            if (popped is Screen.Player) {
                onPlayerExit()
            } else if (popped == null) {
                onFinish()
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
                        appState.navigateToPlayer(item.uriString.toUri())
                    },
                    onOpenSettings = {
                        appState.navigateToSettings()
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
                        playerViewModel.onUserExit()
                        onPlayerExit()
                        appState.popBack()
                    },
                    onOpenSettings = {
                        appState.navigateToSettings()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Screen.Settings> {
                SettingsScreen(
                    viewModel = hiltViewModel(),
                    onBack = {
                        appState.popBack()
                    },
                    onOpenFolderManagement = {
                        appState.navigateToFolderManagement()
                    },
                    onNavigateToMpvConfig = {
                        appState.navigateToMpvConfig()
                    },
                    onNavigateToGestures = {
                        appState.navigateToGestureSettings()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Screen.FolderManagement> {
                FolderManagementScreen(
                    viewModel = hiltViewModel(),
                    onBack = {
                        appState.popBack()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Screen.MpvConfigEditor> {
                MpvConfigEditorScreen(
                    onBack = {
                        appState.popBack()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            entry<Screen.GestureSettings> {
                GestureSettingsScreen(
                    onBack = {
                        appState.popBack()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    )
}
