package dev.gyro.core.designsystem.icon

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min

class GyroIconsTest {

    private val expectedDirectionalIcons = setOf(
        "ic_back",
        "ic_chevron_right",
        "ic_sort",
        "ic_playlist_play",
        "ic_repeat",
        "ic_repeat_one",
        "ic_volume_high",
        "ic_volume_low",
        "ic_volume_mute",
        "ic_help",
    )

    private fun resolveDrawableDir(): File =
        listOf(
            File("src/main/res/drawable"),
            File("core/designsystem/src/main/res/drawable"),
        ).firstOrNull { it.isDirectory }
            ?: error("Could not locate core/designsystem/src/main/res/drawable from ${File(".").absolutePath}")

    private fun listIconXmlFiles(): List<File> =
        resolveDrawableDir()
            .listFiles { file -> file.isFile && file.name.startsWith("ic_") && file.extension == "xml" }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun parseXml(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    @Test
    fun iconSetMatchesDrawableXmlFiles() {
        assertEquals(60, GyroIcons.allDrawableNames.size)
        assertEquals(
            "图标资源名必须唯一",
            GyroIcons.allDrawableNames.size,
            GyroIcons.allDrawableNames.distinct().size,
        )
        assertEquals(60, GyroIcons.allDrawableIds.size)
        assertEquals(
            "图标资源 ID 必须唯一",
            GyroIcons.allDrawableIds.size,
            GyroIcons.allDrawableIds.distinct().size,
        )

        val diskNames = listIconXmlFiles().map { it.nameWithoutExtension }.toSet()
        assertEquals(
            "磁盘 drawable XML 清单与 GyroIcons 注册表必须一一对应",
            GyroIcons.allDrawableNames.toSet(),
            diskNames,
        )
    }

    @Test
    fun allXmlDrawablesFollowMaterialSymbolsContract() {
        val files = listIconXmlFiles()
        assertEquals(60, files.size)

        files.forEach { file ->
            val raw = file.readText()
            assertFalse("${file.name} 不应引用 ?attr/", raw.contains("?attr/"))
            assertFalse("${file.name} 不应引用 @android:", raw.contains("@android:"))
            assertFalse("${file.name} 不应引用 @color/", raw.contains("@color/"))

            val root = parseXml(file)
            assertEquals("${file.name} root tag", "vector", root.tagName)
            assertEquals("${file.name} width", "24dp", root.getAttributeNS(ANDROID_NS, "width"))
            assertEquals("${file.name} height", "24dp", root.getAttributeNS(ANDROID_NS, "height"))
            assertEquals("${file.name} viewportWidth", "960", root.getAttributeNS(ANDROID_NS, "viewportWidth"))
            assertEquals("${file.name} viewportHeight", "960", root.getAttributeNS(ANDROID_NS, "viewportHeight"))
            assertEquals("${file.name} 不应设置 android:tint", "", root.getAttributeNS(ANDROID_NS, "tint"))

            val pathElements = (0 until root.childNodes.length)
                .map { root.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == "path" }
            assertTrue("${file.name} 至少包含一条 <path>", pathElements.isNotEmpty())

            pathElements.forEach { pathEl ->
                assertEquals(
                    "${file.name} fillColor",
                    "#FFFFFFFF",
                    pathEl.getAttributeNS(ANDROID_NS, "fillColor"),
                )
                assertTrue(
                    "${file.name} pathData 不能为空",
                    pathEl.getAttributeNS(ANDROID_NS, "pathData").isNotBlank(),
                )
            }
        }
    }

    @Test
    fun everyIconPathFitsInside960Viewport() {
        val parser = PathParser()

        listIconXmlFiles().forEach { file ->
            val root = parseXml(file)
            val pathNodesList = (0 until root.childNodes.length)
                .map { root.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == "path" }
                .map { pathEl ->
                    val pathData = pathEl.getAttributeNS(ANDROID_NS, "pathData")
                    parser.pathStringToNodes(pathData)
                }

            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY

            pathNodesList.forEach { nodes ->
                assertTrue("${file.name} pathNodes 不能为空", nodes.isNotEmpty())
                var cx = 0f
                var cy = 0f
                var sx = 0f
                var sy = 0f

                fun recordPoint(x: Float, y: Float) {
                    assertTrue("${file.name} 存在非有限坐标 ($x, $y)", x.isFinite() && y.isFinite())
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }

                nodes.forEach { node ->
                    when (node) {
                        is PathNode.MoveTo -> {
                            cx = node.x
                            cy = node.y
                            sx = cx
                            sy = cy
                        }
                        is PathNode.RelativeMoveTo -> {
                            cx += node.dx
                            cy += node.dy
                            sx = cx
                            sy = cy
                        }
                        is PathNode.LineTo -> {
                            cx = node.x
                            cy = node.y
                        }
                        is PathNode.RelativeLineTo -> {
                            cx += node.dx
                            cy += node.dy
                        }
                        is PathNode.HorizontalTo -> {
                            cx = node.x
                        }
                        is PathNode.RelativeHorizontalTo -> {
                            cx += node.dx
                        }
                        is PathNode.VerticalTo -> {
                            cy = node.y
                        }
                        is PathNode.RelativeVerticalTo -> {
                            cy += node.dy
                        }
                        is PathNode.QuadTo -> {
                            cx = node.x2
                            cy = node.y2
                        }
                        is PathNode.RelativeQuadTo -> {
                            cx += node.dx2
                            cy += node.dy2
                        }
                        is PathNode.ReflectiveQuadTo -> {
                            cx = node.x
                            cy = node.y
                        }
                        is PathNode.RelativeReflectiveQuadTo -> {
                            cx += node.dx
                            cy += node.dy
                        }
                        is PathNode.CurveTo -> {
                            cx = node.x3
                            cy = node.y3
                        }
                        is PathNode.RelativeCurveTo -> {
                            cx += node.dx3
                            cy += node.dy3
                        }
                        is PathNode.ReflectiveCurveTo -> {
                            cx = node.x2
                            cy = node.y2
                        }
                        is PathNode.RelativeReflectiveCurveTo -> {
                            cx += node.dx2
                            cy += node.dy2
                        }
                        is PathNode.ArcTo -> {
                            cx = node.arcStartX
                            cy = node.arcStartY
                        }
                        is PathNode.RelativeArcTo -> {
                            cx += node.arcStartDx
                            cy += node.arcStartDy
                        }
                        is PathNode.Close -> {
                            cx = sx
                            cy = sy
                        }
                    }
                    recordPoint(cx, cy)
                }
            }

            assertTrue("${file.name} minX=$minX < 0", minX >= 0f)
            assertTrue("${file.name} maxX=$maxX > 960", maxX <= 960f)
            assertTrue("${file.name} minY=$minY < 0", minY >= 0f)
            assertTrue("${file.name} maxY=$maxY > 960", maxY <= 960f)

            val span = max(maxX - minX, maxY - minY)
            assertTrue("${file.name} 尺寸过小 span=$span，疑为非 960 坐标系", span >= 200f)
        }
    }

    @Test
    fun directionalIconsEnableAutoMirrored() {
        listIconXmlFiles().forEach { file ->
            val baseName = file.nameWithoutExtension
            val root = parseXml(file)
            val autoMirrored = root.getAttributeNS(ANDROID_NS, "autoMirrored")
            if (baseName in expectedDirectionalIcons) {
                assertEquals("${file.name} 方向性图标应开启 autoMirrored", "true", autoMirrored)
            } else {
                assertEquals("${file.name} 非方向性图标不应开启 autoMirrored", "", autoMirrored)
            }
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
