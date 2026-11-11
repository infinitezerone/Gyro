package dev.gyro.engine.api.extension

import android.net.Uri
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Extension for engines supporting real-time GLSL post-processing shaders (e.g. Anime4K).
 */
interface ShaderExtension {
    val shaderState: StateFlow<GlslShaderState>
    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null)
    fun clearShader()
    fun getCustomShaders(): List<File> = emptyList()
    fun importAndSetCustomShader(uri: Uri): Result<String> = Result.failure(UnsupportedOperationException())
}
