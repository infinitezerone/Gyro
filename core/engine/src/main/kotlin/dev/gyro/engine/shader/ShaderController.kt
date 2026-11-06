package dev.gyro.engine.shader

import android.net.Uri
import android.util.Log
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.MpvNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller responsible for GLSL post-processing shaders (CAS, Anime4K, custom shaders).
 */
interface ShaderController {
    val shaderState: StateFlow<GlslShaderState>

    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null)
    fun clearShader()
    fun importAndSetCustomShader(uri: Uri): Result<String>
    fun getCustomShaders(): List<File>
    fun extractBuiltInShaders(): Map<GlslShader, File>
    fun restoreActiveShader()
}

@Singleton
class RealShaderController @Inject constructor(
    private val bridge: MpvNativeBridge,
    private val shaderManager: ShaderManager
) : ShaderController {
    companion object {
        private const val TAG = "ShaderController"
    }

    private val _shaderState = MutableStateFlow(GlslShaderState())
    override val shaderState: StateFlow<GlslShaderState> = _shaderState.asStateFlow()

    override fun extractBuiltInShaders(): Map<GlslShader, File> =
        shaderManager.extractBuiltInShaders()

    override fun restoreActiveShader() {
        val currentShader = _shaderState.value
        val currentPath = currentShader.currentPath
        if (currentShader.activeShader != GlslShader.OFF && currentPath != null) {
            bridge.command(arrayOf("change-list", "glsl-shaders", "set", currentPath))
        }
    }

    override fun setShader(shader: GlslShader, customPath: String?, customName: String?) {
        when (shader) {
            GlslShader.OFF -> {
                clearShader()
            }
            GlslShader.CAS -> {
                val file = shaderManager.getBuiltInShaderFile(GlslShader.CAS)
                if (file != null && file.exists()) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", file.absolutePath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.CAS,
                        currentPath = file.absolutePath,
                        displayName = "AMD FidelityFX CAS"
                    )
                    Log.i(TAG, "Applied CAS shader: ${file.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to apply CAS shader: file missing")
                }
            }
            GlslShader.ANIME4K -> {
                val file = shaderManager.getBuiltInShaderFile(GlslShader.ANIME4K)
                if (file != null && file.exists()) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", file.absolutePath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.ANIME4K,
                        currentPath = file.absolutePath,
                        displayName = "Anime4K Lite"
                    )
                    Log.i(TAG, "Applied Anime4K Lite shader: ${file.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to apply Anime4K Lite shader: file missing")
                }
            }
            GlslShader.CUSTOM -> {
                if (!customPath.isNullOrBlank() && File(customPath).exists()) {
                    val displayName = customName ?: File(customPath).nameWithoutExtension
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", customPath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.CUSTOM,
                        currentPath = customPath,
                        displayName = displayName
                    )
                    Log.i(TAG, "Applied custom shader: $customPath ($displayName)")
                } else {
                    Log.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
                }
            }
        }
    }

    override fun clearShader() {
        bridge.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        _shaderState.value = GlslShaderState(
            activeShader = GlslShader.OFF,
            currentPath = null,
            displayName = null
        )
        Log.i(TAG, "Cleared glsl-shaders")
    }

    override fun importAndSetCustomShader(uri: Uri): Result<String> {
        return shaderManager.importCustomShader(uri).map { file ->
            val name = file.nameWithoutExtension
            setShader(GlslShader.CUSTOM, customPath = file.absolutePath, customName = name)
            file.absolutePath
        }
    }

    override fun getCustomShaders(): List<File> = shaderManager.listCustomShaders()
}
