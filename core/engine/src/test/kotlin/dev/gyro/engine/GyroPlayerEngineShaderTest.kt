package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import dev.gyro.core.model.GlslShader
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.video.ShaderManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GyroPlayerEngineShaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var shaderManager: ShaderManager
    private lateinit var engine: GyroPlayerEngine

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("engine_shaders")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        shaderManager = ShaderManager(testContext, filesDir)

        // Seed built-in shader files on disk
        val builtInDir = File(filesDir, ShaderManager.BUILT_IN_DIR).apply { mkdirs() }
        File(builtInDir, "anime4k_lite.glsl").writeText("// Anime4K GLSL")

        engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge,
            shaders = dev.gyro.engine.video.ShaderController(fakeBridge, shaderManager)
        )
        engine.warmUp()
    }

    @Test
    fun testSetShaderAnime4K_dispatchesCommandAndUpdatesState() {
        engine.shaders.setShader(GlslShader.ANIME4K)

        val animeFile = File(filesDir, "${ShaderManager.BUILT_IN_DIR}/anime4k_lite.glsl")
        val expectedCommand = listOf("change-list", "glsl-shaders", "set", animeFile.absolutePath)
        assertTrue(fakeBridge.commands.contains(expectedCommand))

        val state = engine.shaders.shaderState.value
        assertEquals(GlslShader.ANIME4K, state.activeShader)
        assertEquals(animeFile.absolutePath, state.currentPath)
        assertEquals("Anime4K Lite", state.displayName)
    }

    @Test
    fun testSetShaderCustom_withValidPath_succeeds() {
        val customFile = tempFolder.newFile("custom_cinematic.glsl").apply {
            writeText("// Custom shader")
        }

        engine.shaders.setShader(GlslShader.CUSTOM, customPath = customFile.absolutePath, customName = "Cinematic")

        val expectedCommand = listOf("change-list", "glsl-shaders", "set", customFile.absolutePath)
        assertTrue(fakeBridge.commands.contains(expectedCommand))

        val state = engine.shaders.shaderState.value
        assertEquals(GlslShader.CUSTOM, state.activeShader)
        assertEquals(customFile.absolutePath, state.currentPath)
        assertEquals("Cinematic", state.displayName)
    }

    @Test
    fun testClearShader_dispatchesClrCommand() {
        // Set first
        engine.shaders.setShader(GlslShader.ANIME4K)
        assertEquals(GlslShader.ANIME4K, engine.shaders.shaderState.value.activeShader)

        // Then clear
        engine.shaders.clearShader()

        val expectedClr = listOf("change-list", "glsl-shaders", "clr", "")
        assertTrue(fakeBridge.commands.contains(expectedClr))

        val state = engine.shaders.shaderState.value
        assertEquals(GlslShader.OFF, state.activeShader)
        assertNull(state.currentPath)
        assertNull(state.displayName)
    }

    @Test
    fun testRelease_resetsShaders() {
        engine.shaders.setShader(GlslShader.ANIME4K)
        assertEquals(GlslShader.ANIME4K, engine.shaders.shaderState.value.activeShader)

        engine.release()
        assertEquals(GlslShader.OFF, engine.shaders.shaderState.value.activeShader)
        assertNull(engine.shaders.shaderState.value.currentPath)
    }
}
