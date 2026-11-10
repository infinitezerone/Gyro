package dev.gyro.engine.api.extension

import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.api.EngineExtension
import kotlinx.coroutines.flow.StateFlow

/**
 * Extension for engines supporting real-time GLSL post-processing shaders (e.g. Anime4K).
 */
interface ShaderExtension : EngineExtension {
    val shaderState: StateFlow<GlslShaderState>
    fun setShader(shader: GlslShader, customPath: String? = null, customName: String? = null)
    fun clearShader()
}
