package dev.gyro.player.navigation

import android.net.TestUri
import android.net.Uri
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import dev.gyro.core.navigation.Screen
import dev.gyro.core.navigation.UriSerializer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GyroAppStateTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        UriSerializer.uriParser = { str -> TestUri(str) }
    }

    @After
    fun tearDown() {
        UriSerializer.uriParser = { str -> Uri.parse(str) }
    }

    @Test
    fun navigateToPlayer_pushesPlayerWhenNotPresent() {
        val backStack = NavBackStack<NavKey>(Screen.Home)
        val appState = GyroAppState(backStack, testScope)

        val uri = TestUri("file:///test/video1.mp4")
        appState.navigateToPlayer(uri)

        assertEquals(2, backStack.size)
        assertEquals(Screen.Home, backStack[0])
        assertEquals(Screen.Player(uri), backStack[1])
        assertTrue(appState.isPlayerOnStack)
    }

    @Test
    fun navigateToPlayer_replacesTopWhenAlreadyOnPlayer_singleTop() {
        val uri1 = TestUri("file:///test/video1.mp4")
        val uri2 = TestUri("file:///test/video2.mp4")
        val backStack = NavBackStack<NavKey>(Screen.Home, Screen.Player(uri1))
        val appState = GyroAppState(backStack, testScope)

        appState.navigateToPlayer(uri2)

        // Stack size must remain 2 (single-top replacement, no duplicate Player stacking)
        assertEquals(2, backStack.size)
        assertEquals(Screen.Home, backStack[0])
        assertEquals(Screen.Player(uri2), backStack[1])
    }

    @Test
    fun navigateToPlayer_ignoresWhenSameUriAlreadyTop() {
        val uri = TestUri("file:///test/video1.mp4")
        val backStack = NavBackStack<NavKey>(Screen.Home, Screen.Player(uri))
        val appState = GyroAppState(backStack, testScope)

        appState.navigateToPlayer(uri)

        assertEquals(2, backStack.size)
        assertEquals(Screen.Player(uri), backStack[1])
    }

    @Test
    fun handleIncomingUri_replacesWhenAlreadyOnPlayer() {
        val uri1 = TestUri("file:///test/video1.mp4")
        val uri2 = TestUri("file:///test/video2.mp4")
        val backStack = NavBackStack<NavKey>(Screen.Home, Screen.Player(uri1))
        val appState = GyroAppState(backStack, testScope)

        appState.handleIncomingUri(uri2)

        assertEquals(2, backStack.size)
        assertEquals(Screen.Home, backStack[0])
        assertEquals(Screen.Player(uri2), backStack[1])
    }

    @Test
    fun handleIncomingUri_buildsSyntheticBackStackWhenOnOtherScreen() {
        val backStack = NavBackStack<NavKey>(Screen.Home, Screen.Settings, Screen.FolderManagement)
        val appState = GyroAppState(backStack, testScope)

        val uri = TestUri("file:///test/video.mp4")
        appState.handleIncomingUri(uri)

        // Synthetic backstack strictly resets to canonical [Home, Player(uri)]
        assertEquals(2, backStack.size)
        assertEquals(Screen.Home, backStack[0])
        assertEquals(Screen.Player(uri), backStack[1])
    }

    @Test
    fun popBack_popsUntilRoot() {
        val backStack = NavBackStack<NavKey>(Screen.Home, Screen.Settings)
        val appState = GyroAppState(backStack, testScope)

        val popped = appState.popBack()
        assertEquals(Screen.Settings, popped)
        assertEquals(1, backStack.size)

        // At root, popBack returns null without removing root
        val poppedRoot = appState.popBack()
        assertNull(poppedRoot)
        assertEquals(1, backStack.size)
        assertEquals(Screen.Home, backStack[0])
    }

    @Test
    fun navigateToSettings_pushesSettings() {
        val backStack = NavBackStack<NavKey>(Screen.Home)
        val appState = GyroAppState(backStack, testScope)

        appState.navigateToSettings()

        assertEquals(2, backStack.size)
        assertEquals(Screen.Settings, backStack[1])
        assertFalse(appState.isPlayerOnStack)
    }

    @Test
    fun removePlayerScreens_removesPlayerAndPreservesNonPlayerEntries() {
        val uri1 = TestUri("file:///test/video1.mp4")

        val stackHomePlayer = NavBackStack<NavKey>(Screen.Home, Screen.Player(uri1))
        GyroAppState(stackHomePlayer, testScope).removePlayerScreens()
        assertEquals(listOf(Screen.Home), stackHomePlayer.toList())

        val stackOnlyPlayer = NavBackStack<NavKey>(Screen.Player(uri1))
        GyroAppState(stackOnlyPlayer, testScope).removePlayerScreens()
        assertEquals(listOf(Screen.Home), stackOnlyPlayer.toList())

        val stackHomePlayerSettings = NavBackStack<NavKey>(Screen.Home, Screen.Player(uri1), Screen.Settings)
        GyroAppState(stackHomePlayerSettings, testScope).removePlayerScreens()
        assertEquals(listOf(Screen.Home, Screen.Settings), stackHomePlayerSettings.toList())
    }
}
