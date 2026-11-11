package dev.gyro.engine.api.extension

import android.net.Uri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.api.EngineExtension
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Extension for engines supporting real-time GLSL post-processing shaders (e.g. Anime4K).
 */
interface ShaderExtension : EngineExtension {
    val shaderState: StateFlow<GlslShaderState>
    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null)
    fun clearShader()
    fun getCustomShaders(): List<File> = emptyList()
    fun importAndSetCustomShader(uri: Uri): Result<String> = Result.failure(UnsupportedOperationException())
}
