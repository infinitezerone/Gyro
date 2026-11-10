package dev.gyro.core.model

/**
 * Supported GLSL shader profiles for Gyro player engine.
 */
enum class GlslShader(val id: String) {
    OFF("off"),
    ANIME4K("anime4k"),
    CAS("cas"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String): GlslShader {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF
        }
    }
}

/**
 * State of active GLSL shader on Gyro player engine.
 */
data class GlslShaderState(
    val activeShader: GlslShader = GlslShader.OFF,
    val currentPath: String? = null,
    val displayName: String? = null
)
