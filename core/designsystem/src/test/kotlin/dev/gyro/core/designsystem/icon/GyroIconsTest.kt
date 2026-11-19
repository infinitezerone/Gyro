package dev.gyro.core.designsystem.icon

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroIconsTest {

    private val allIcons = listOf(
        // 播放控制
        GyroIcons.Play,
        GyroIcons.Pause,
        GyroIcons.Replay,
        GyroIcons.SkipPrevious,
        GyroIcons.SkipNext,
        GyroIcons.FastRewind,
        GyroIcons.FastForward,
        // 播放器悬浮层
        GyroIcons.FullscreenEnter,
        GyroIcons.FullscreenExit,
        GyroIcons.Lock,
        GyroIcons.LockOpen,
        GyroIcons.Screenshot,
        GyroIcons.Pip,
        // 手势反馈
        GyroIcons.Brightness,
        GyroIcons.VolumeHigh,
        GyroIcons.VolumeLow,
        GyroIcons.VolumeMute,
        GyroIcons.Timer,
        // 导航与通用动作
        GyroIcons.Back,
        GyroIcons.More,
        GyroIcons.MoreVert,
        GyroIcons.ChevronRight,
        GyroIcons.Close,
        GyroIcons.Add,
        GyroIcons.Check,
        GyroIcons.Search,
        GyroIcons.Sort,
        GyroIcons.Refresh,
        GyroIcons.RestartAlt,
        GyroIcons.Help,
        GyroIcons.Error,
        GyroIcons.Settings,
        // 文件与编辑器
        GyroIcons.Folder,
        GyroIcons.FolderOpen,
        GyroIcons.FolderOff,
        GyroIcons.CreateNewFolder,
        GyroIcons.Delete,
        GyroIcons.Save,
        GyroIcons.ContentCopy,
        // 音视频参数
        GyroIcons.ClosedCaption,
        GyroIcons.ClosedCaptionOutline,
        GyroIcons.PlaylistPlay,
        GyroIcons.GraphicEq,
        GyroIcons.Tune,
        GyroIcons.Speed,
        GyroIcons.Memory,
        GyroIcons.AutoAwesome,
        GyroIcons.Gradient,
        GyroIcons.Palette,
        GyroIcons.Analytics,
        GyroIcons.VideoLibrary,
        GyroIcons.Movie,
        GyroIcons.Monitor,
        GyroIcons.FitScreen,
    )

    /** 增删图标时同步更新上面的清单与这里的数量。 */
    @Test
    fun iconSetIsComplete() {
        assertEquals(54, allIcons.size)
    }

    @Test
    fun allIconsShareMaterialSymbolsGrid() {
        allIcons.forEach { icon ->
            assertEquals("${icon.name} width", 24.dp, icon.defaultWidth)
            assertEquals("${icon.name} height", 24.dp, icon.defaultHeight)
            assertEquals("${icon.name} viewportWidth", 960f, icon.viewportWidth, 0.001f)
            assertEquals("${icon.name} viewportHeight", 960f, icon.viewportHeight, 0.001f)
            assertTrue("${icon.name} should contain vector nodes", icon.root.size > 0)
        }
    }

    /**
     * Material Symbols 的字形画在 y ∈ [-960, 0]，必须由外层 group 下移 960 才能落进 viewport。
     * 漏掉平移不会编译报错，只会让图标整体跑到画布外，因此单独锁定该前提。
     */
    @Test
    fun everyIconIsShiftedIntoSymbolsViewport() {
        allIcons.forEach { icon ->
            val group = icon.root[0] as VectorGroup
            assertEquals("${icon.name} translationY", 960f, group.translationY, 0.001f)
            val path = group[0] as VectorPath
            assertTrue("${icon.name} path data", path.pathData.isNotEmpty())
        }
    }

    @Test
    fun iconNamesAreUnique() {
        val names = allIcons.map { it.name }
        assertEquals("图标名用于比较与调试，必须唯一", allIcons.size, names.distinct().size)
    }
}
