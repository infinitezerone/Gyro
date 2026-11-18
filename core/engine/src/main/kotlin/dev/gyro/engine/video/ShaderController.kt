package dev.gyro.engine.video

import android.net.Uri
import android.os.Looper
import dev.gyro.core.model.GlslShader
import dev.gyro.core.model.GlslShaderState
import dev.gyro.engine.EngineSubsystem
import dev.gyro.engine.MpvNativeBridge
import dev.gyro.engine.state.PlaybackStore
import dev.gyro.engine.trace.EngineLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Controller responsible for GLSL post-processing shaders (Anime4K, custom shaders).
 */
class ShaderController(
    private val bridge: MpvNativeBridge,
    private val shaderManager: ShaderManager = ShaderManager(),
    private val store: PlaybackStore = PlaybackStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scopeProvider: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + ioDispatcher) },
) : EngineSubsystem, dev.gyro.engine.api.extension.ShaderExtension {
    companion object {
        private const val TAG = "ShaderController"

        private val BUILT_IN_DISPLAY_NAMES = mapOf(
            GlslShader.ANIME4K to "Anime4K Lite",
            GlslShader.CAS to "AMD CAS",
        )
    }

    private val _shaderState = store.property({ it.shaderState }) { s, v -> s.copy(shaderState = v) }
    override val shaderState: StateFlow<GlslShaderState> = _shaderState.asStateFlow()

    private val applyLock = Any()
    private val shaderGeneration = AtomicLong(0L)

    @Volatile
    private var pendingShaderJob: Job? = null

    @Volatile
    private var boundScopeProvider: (() -> CoroutineScope)? = null

    internal fun bindScopeProvider(provider: () -> CoroutineScope) {
        boundScopeProvider = provider
    }

    private fun resolveScope(): CoroutineScope = (boundScopeProvider ?: scopeProvider).invoke()

    private fun isOffMainThread(): Boolean =
        runCatching { Looper.getMainLooper()?.thread !== Thread.currentThread() }.getOrDefault(true)

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

    override fun setShader(shader: GlslShader, customPath: String?, customName: String?) {
        when (shader) {
            GlslShader.OFF -> clearShader()
            GlslShader.ANIME4K,
            GlslShader.CAS -> setBuiltInShader(shader)
            GlslShader.CUSTOM -> setCustomShader(customPath, customName)
        }
    }

    private fun setBuiltInShader(shader: GlslShader) {
        val displayName = BUILT_IN_DISPLAY_NAMES[shader] ?: return
        val cachedFile = shaderManager.getCachedBuiltInShaderFile(shader)
        if (cachedFile != null || isOffMainThread()) {
            val file = cachedFile ?: shaderManager.getBuiltInShaderFile(shader)
            if (file != null && file.exists()) {
                synchronized(applyLock) {
                    shaderGeneration.incrementAndGet()
                    pendingShaderJob?.cancel()
                    pendingShaderJob = null
                    applyShaderLocked(shader, file.absolutePath, displayName)
                }
                EngineLog.i(TAG, "Applied $displayName shader: ${file.absolutePath}")
            } else {
                EngineLog.e(TAG, "Failed to apply $displayName shader: file missing")
            }
            return
        }

        val gen: Long
        synchronized(applyLock) {
            gen = shaderGeneration.incrementAndGet()
            pendingShaderJob?.cancel()
            pendingShaderJob = null
            _shaderState.value = GlslShaderState(
                activeShader = shader,
                currentPath = null,
                displayName = displayName
            )
        }

        val job = resolveScope().launch(ioDispatcher) {
            val file = shaderManager.getBuiltInShaderFile(shader)
            if (!isActive || shaderGeneration.get() != gen) return@launch
            if (file != null && file.exists()) {
                synchronized(applyLock) {
                    if (!isActive || shaderGeneration.get() != gen) return@launch
                    applyShaderLocked(shader, file.absolutePath, displayName)
                }
                EngineLog.i(TAG, "Applied $displayName shader: ${file.absolutePath}")
            } else {
                synchronized(applyLock) {
                    if (!isActive || shaderGeneration.get() != gen) return@launch
                    _shaderState.value = GlslShaderState(
                        activeShader = GlslShader.OFF,
                        currentPath = null,
                        displayName = null
                    )
                }
                EngineLog.e(TAG, "Failed to apply $displayName shader: file missing")
            }
        }
        synchronized(applyLock) {
            if (shaderGeneration.get() == gen) {
                pendingShaderJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun setCustomShader(customPath: String?, customName: String?) {
        if (customPath.isNullOrBlank()) {
            EngineLog.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
            return
        }
        val displayName = customName ?: File(customPath).nameWithoutExtension
        if (isOffMainThread()) {
            if (File(customPath).exists()) {
                synchronized(applyLock) {
                    shaderGeneration.incrementAndGet()
                    pendingShaderJob?.cancel()
                    pendingShaderJob = null
                    applyShaderLocked(GlslShader.CUSTOM, customPath, displayName)
                }
                EngineLog.i(TAG, "Applied custom shader: $customPath ($displayName)")
            } else {
                EngineLog.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
            }
            return
        }

        val gen: Long
        synchronized(applyLock) {
            gen = shaderGeneration.incrementAndGet()
            pendingShaderJob?.cancel()
            pendingShaderJob = null
        }

        val job = resolveScope().launch(ioDispatcher) {
            val exists = File(customPath).exists()
            if (!isActive || shaderGeneration.get() != gen) return@launch
            if (exists) {
                synchronized(applyLock) {
                    if (!isActive || shaderGeneration.get() != gen) return@launch
                    applyShaderLocked(GlslShader.CUSTOM, customPath, displayName)
                }
                EngineLog.i(TAG, "Applied custom shader: $customPath ($displayName)")
            } else {
                EngineLog.w(TAG, "Custom shader path invalid or file does not exist: $customPath")
            }
        }
        synchronized(applyLock) {
            if (shaderGeneration.get() == gen) {
                pendingShaderJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun applyShaderLocked(shader: GlslShader, path: String, displayName: String) {
        bridge.command(arrayOf("change-list", "glsl-shaders", "set", path))
        _shaderState.value = GlslShaderState(
            activeShader = shader,
            currentPath = path,
            displayName = displayName
        )
    }

    override fun clearShader() {
        synchronized(applyLock) {
            shaderGeneration.incrementAndGet()
            pendingShaderJob?.cancel()
            pendingShaderJob = null
            bridge.command(arrayOf("change-list", "glsl-shaders", "clr", ""))
            _shaderState.value = GlslShaderState(
                activeShader = GlslShader.OFF,
                currentPath = null,
                displayName = null
            )
        }
        EngineLog.i(TAG, "Cleared glsl-shaders")
    }

    override suspend fun importAndSetCustomShader(uri: Uri): Result<String> {
        return withContext(ioDispatcher) {
            shaderManager.importCustomShader(uri).map { file ->
                val name = file.nameWithoutExtension
                setShader(GlslShader.CUSTOM, customPath = file.absolutePath, customName = name)
                file.absolutePath
            }
        }
    }

    override fun getCustomShaders(): List<File> = shaderManager.listCustomShaders()

    override suspend fun listCustomShaders(): List<File> =
        withContext(ioDispatcher) { shaderManager.listCustomShaders() }
}
