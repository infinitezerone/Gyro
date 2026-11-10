package dev.gyro.engine.video

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.gyro.core.model.GlslShader
import dev.gyro.engine.trace.EngineLog
import java.io.File
import java.io.IOException

/**
 * Manages extraction of built-in shader assets and caching of SAF imported custom shaders.
 */
class ShaderManager(
    private val context: Context? = null,
    private val baseDir: File = runCatching { context?.filesDir }.getOrNull()
        ?: File(System.getProperty("java.io.tmpdir"), "gyro_shaders")
) {

    companion object {
        private const val TAG = "ShaderManager"
        const val BUILT_IN_DIR = "shaders/built_in"
        const val CUSTOM_DIR = "shaders/custom"

        val BUILT_IN_ASSET_NAMES = mapOf(
            GlslShader.ANIME4K to "anime4k_lite.glsl",
            GlslShader.CAS to "cas.glsl"
        )
    }

    private val builtInDir: File by lazy {
        File(baseDir, BUILT_IN_DIR).apply { mkdirs() }
    }

    private val customDir: File by lazy {
        File(baseDir, CUSTOM_DIR).apply { mkdirs() }
    }

    /**
     * Extracts built-in shader assets from APK to internal storage.
     */
    fun extractBuiltInShaders(): Map<GlslShader, File> {
        val extracted = mutableMapOf<GlslShader, File>()
        for ((shader, assetName) in BUILT_IN_ASSET_NAMES) {
            val targetFile = File(builtInDir, assetName)
            val ctx = context
            if (ctx != null) {
                try {
                    val assetBytes = ctx.assets.open("shaders/$assetName").use { it.readBytes() }
                    if (!targetFile.exists() || targetFile.length() != assetBytes.size.toLong()) {
                        targetFile.writeBytes(assetBytes)
                        EngineLog.i(TAG, "Extracted/Updated built-in shader $assetName (${assetBytes.size} bytes) to ${targetFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    EngineLog.e(TAG, "Failed to extract built-in shader asset $assetName", e)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0L) {
                extracted[shader] = targetFile
            }
        }
        return extracted
    }

    /**
     * Retrieves the absolute File for a built-in shader, extracting on-demand if absent or outdated.
     */
    fun getBuiltInShaderFile(shader: GlslShader): File? {
        val assetName = BUILT_IN_ASSET_NAMES[shader] ?: return null
        val targetFile = File(builtInDir, assetName)
        val ctx = context
        if (ctx != null) {
            try {
                val assetBytes = ctx.assets.open("shaders/$assetName").use { it.readBytes() }
                if (!targetFile.exists() || targetFile.length() != assetBytes.size.toLong()) {
                    targetFile.writeBytes(assetBytes)
                    EngineLog.i(TAG, "Updated built-in shader $assetName (${assetBytes.size} bytes) on demand")
                }
            } catch (e: Exception) {
                EngineLog.e(TAG, "Failed to extract built-in shader asset $assetName on demand", e)
            }
        }
        return targetFile.takeIf { it.exists() && it.length() > 0L }
    }

    /**
     * Caches a user-selected SAF custom shader into `filesDir/shaders/custom/`.
     * Sanitizes file name against directory traversal.
     */
    fun importCustomShader(uri: Uri): Result<File> = runCatching {
        val ctx = context ?: throw IOException("Context is required to import custom shader from URI: $uri")
        val displayName = queryDisplayName(ctx, uri) ?: "custom_${System.currentTimeMillis()}.glsl"
        val safeName = sanitizeFileName(displayName)
        val targetFile = File(customDir, safeName)

        val resolver = ctx.contentResolver
        val bytesCopied = resolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Failed to open input stream for URI: $uri")

        if (bytesCopied == 0L || !targetFile.exists()) {
            targetFile.delete()
            throw IOException("Imported shader file is empty: $safeName")
        }

        EngineLog.i(TAG, "Imported custom shader to ${targetFile.absolutePath} ($bytesCopied bytes)")
        targetFile
    }

    /**
     * Lists all imported custom shaders.
     */
    fun listCustomShaders(): List<File> {
        if (!customDir.exists()) return emptyList()
        return customDir.listFiles { file ->
            file.isFile && file.extension.equals("glsl", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    /**
     * Deletes a cached custom shader file.
     */
    fun deleteCustomShader(file: File): Boolean {
        return if (file.parentFile?.canonicalPath == customDir.canonicalPath) {
            file.delete()
        } else false
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".glsl", ignoreCase = true)) clean else "$clean.glsl"
    }
}
