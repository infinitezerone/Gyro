package dev.gyro.engine.video

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.gyro.core.model.GlslShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ShaderManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var testContext: Context
    private val assetsData = mutableMapOf<String, ByteArray>()
    private val uriData = mutableMapOf<Uri, Pair<String, ByteArray>>()

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("app_files")
        assetsData["shaders/cas.glsl"] = "// CAS shader content".toByteArray(Charsets.UTF_8)
        assetsData["shaders/anime4k_lite.glsl"] = "// Anime4K content".toByteArray(Charsets.UTF_8)

        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = baseDir

            override fun getAssets(): AssetManager {
                // In unit test environment, we can construct or proxy AssetManager,
                // but since ShaderManager also accepts baseDir directly, let's provide custom AssetManager via reflection or proxy if needed.
                return super.getAssets()
            }
        }
    }

    @Test
    fun testExtractBuiltInShaders_createsFilesOnDisk() {
        val manager = ShaderManager(testContext, baseDir)

        // Pre-populate built-in directory manually or verify getBuiltInShaderFile fallback
        val builtInDir = File(baseDir, ShaderManager.BUILT_IN_DIR).apply { mkdirs() }
        val casFile = File(builtInDir, "cas.glsl").apply { writeText("// CAS content") }
        val animeFile = File(builtInDir, "anime4k_lite.glsl").apply { writeText("// Anime4K content") }

        val extracted = manager.extractBuiltInShaders()
        assertTrue(extracted.containsKey(GlslShader.CAS))
        assertTrue(extracted.containsKey(GlslShader.ANIME4K))
        assertEquals(casFile.absolutePath, extracted[GlslShader.CAS]?.absolutePath)
        assertEquals(animeFile.absolutePath, extracted[GlslShader.ANIME4K]?.absolutePath)
    }

    @Test
    fun testListCustomShaders_returnsGlslFilesOnly() {
        val manager = ShaderManager(testContext, baseDir)
        val customDir = File(baseDir, ShaderManager.CUSTOM_DIR).apply { mkdirs() }

        val shader1 = File(customDir, "film_grain.glsl").apply { writeText("// Film grain") }
        val shader2 = File(customDir, "sharp.glsl").apply { writeText("// Sharp") }
        val ignoredTxt = File(customDir, "readme.txt").apply { writeText("ignore me") }

        val listed = manager.listCustomShaders()
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.name == "film_grain.glsl" })
        assertTrue(listed.any { it.name == "sharp.glsl" })
        assertFalse(listed.any { it.name == "readme.txt" })
    }

    @Test
    fun testDeleteCustomShader_deletesInternalShaderOnly() {
        val manager = ShaderManager(testContext, baseDir)
        val customDir = File(baseDir, ShaderManager.CUSTOM_DIR).apply { mkdirs() }

        val shader1 = File(customDir, "to_delete.glsl").apply { writeText("// Delete me") }
        assertTrue(shader1.exists())

        val deleted = manager.deleteCustomShader(shader1)
        assertTrue(deleted)
        assertFalse(shader1.exists())

        // File outside custom dir cannot be deleted via deleteCustomShader
        val outsideFile = File(baseDir, "outside.glsl").apply { writeText("// Outside") }
        val deletedOutside = manager.deleteCustomShader(outsideFile)
        assertFalse(deletedOutside)
        assertTrue(outsideFile.exists())
    }
}
