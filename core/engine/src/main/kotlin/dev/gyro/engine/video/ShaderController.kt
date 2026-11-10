package dev.gyro.engine.video

import android.net.Uri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Controller responsible for GLSL post-processing shaders (Anime4K, custom shaders).
 */
class ShaderController(
    private val bridge: MpvNativeBridge,
    private val shaderManager: ShaderManager = ShaderManager(),
    private val store: PlaybackStore = PlaybackStore()
) : EngineSubsystem {
    companion object {
        private const val TAG = "ShaderController"
    }

    private val _shaderState = store.property({ it.shaderState }) { s, v -> s.copy(shaderState = v) }
    val shaderState: StateFlow<GlslShaderState> = _shaderState.asStateFlow()

    override fun reset() {
        clearShader()
    }

    fun extractBuiltInShaders(): Map<GlslShader, File> =
        shaderManager.extractBuiltInShaders()

    fun restoreActiveShader() {
        val currentShader = _shaderState.value
        val currentPath = currentShader.currentPath
        if (currentShader.activeShader != GlslShader.OFF && currentPath != null) {
            bridge.command(arrayOf("change-list", "glsl-shaders", "set", currentPath))
        }
    }

    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null) {
        when (shader) {
            GlslShader.OFF -> {
                clearShader()
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
                    EngineLog.i(TAG, "Applied Anime4K Lite shader: ${file.absolutePath}")
                } else {
                    EngineLog.e(TAG, "Failed to apply Anime4K Lite shader: file missing")
                }
            }
            GlslShader.CAS -> {
                val file = shaderManager.getBuiltInShaderFile(GlslShader.CAS)
                if (file != null && file.exists()) {
                    bridge.command(arrayOf("change-list", "glsl-shaders", "set", file.absolutePath))
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.CAS,
                        currentPath = file.absolutePath,
                        displayName = "AMD CAS"
                    )
                    EngineLog.i(TAG, "Applied AMD CAS shader: ${file.absolutePath}")
                } else {
                    EngineLog.e(TAG, "Failed to apply AMD CAS shader: file missing")
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
                    EngineLog.i(TAG, "Applied custom shader: $customPath ($displayName)")
                } else {
                    EngineLog.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
                }
            }
        }
    }

    fun clearShader() {
        bridge.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
        _shaderState.value = GlslShaderState(
            activeShader = GlslShader.OFF,
            currentPath = null,
            displayName = null
        )
        EngineLog.i(TAG, "Cleared glsl-shaders")
    }

    fun importAndSetCustomShader(uri: Uri): Result<String> {
        return shaderManager.importCustomShader(uri).map { file ->
            val name = file.nameWithoutExtension
            setShader(GlslShader.CUSTOM, customPath = file.absolutePath, customName = name)
            file.absolutePath
        }
    }

    fun getCustomShaders(): List<File> = shaderManager.listCustomShaders()
}
