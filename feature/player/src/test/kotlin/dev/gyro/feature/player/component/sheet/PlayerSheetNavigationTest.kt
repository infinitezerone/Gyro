package dev.gyro.feature.player.component.sheet

import dev.gyro.core.domain.session.PlaylistRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSheetNavigationTest {

    @Test
    fun allSubRoutesSitDirectlyUnderHubWithinTwoLevels() {
        assertNull(PlayerSheetRoute.SETTINGS_HUB.parent)
        val subRoutes = listOf(
            PlayerSheetRoute.VIDEO,
            PlayerSheetRoute.AUDIO,
            PlayerSheetRoute.SUBTITLES,
            PlayerSheetRoute.SPEED,
            PlayerSheetRoute.STATS_INFO,
            PlayerSheetRoute.PLAYLIST,
            PlayerSheetRoute.CHAPTERS,
        )
        subRoutes.forEach { route ->
            assertEquals(PlayerSheetRoute.SETTINGS_HUB, route.parent)
        }
    }

    @Test
    fun transitionDirectionFollowsBackStackDepthStrictly() {
        assertFalse(isBackTransition(previousDepth = 1, targetDepth = 2))
        assertFalse(isBackTransition(previousDepth = 2, targetDepth = 3))
        assertTrue(isBackTransition(previousDepth = 2, targetDepth = 1))
        assertTrue(isBackTransition(previousDepth = 3, targetDepth = 2))
        assertFalse(isBackTransition(previousDepth = 1, targetDepth = 1))
    }

    @Test
    fun navStatePushesAndPopsWithoutDuplicatingCurrentPage() {
        val nav = SheetNavState(PlayerSheetRoute.SETTINGS_HUB)
        assertFalse(nav.canGoBack)
        assertFalse("根页返回由宿主决定是否关闭抽屉", nav.goBack())

        nav.push(PlayerSheetRoute.VIDEO)
        assertEquals(2, nav.depth)
        assertTrue(nav.canGoBack)
        assertEquals(PlayerSheetRoute.VIDEO, nav.current)

        // 重复点击同一入口不应叠加返回层。
        nav.push(PlayerSheetRoute.VIDEO)
        assertEquals(2, nav.depth)

        assertTrue(nav.goBack())
        assertEquals(PlayerSheetRoute.SETTINGS_HUB, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun navStateReplaceKeepsFlatDepthForPillTabs() {
        val nav = SheetNavState(PlayerSheetRoute.PLAYLIST)
        nav.replace(PlayerSheetRoute.CHAPTERS)

        assertEquals(PlayerSheetRoute.CHAPTERS, nav.current)
        assertEquals(1, nav.depth)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun nextPlaylistRepeatModeCyclesAllThreeModes() {
        assertEquals(
            PlaylistRepeatMode.LOOP_ALL,
            nextPlaylistRepeatMode(PlaylistRepeatMode.SEQUENTIAL),
        )
        assertEquals(
            PlaylistRepeatMode.LOOP_ONE,
            nextPlaylistRepeatMode(PlaylistRepeatMode.LOOP_ALL),
        )
        assertEquals(
            PlaylistRepeatMode.SEQUENTIAL,
            nextPlaylistRepeatMode(PlaylistRepeatMode.LOOP_ONE),
        )
    }
}
