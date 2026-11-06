package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GyroPlayerEngineMpvConfInjectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("engine_mpv_conf")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = this@GyroPlayerEngineMpvConfInjectionTest.filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
    }

    @Test
    fun testProtectedKeysFilter_dropsVoIdleKeepOpen() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                vo=gpu
                idle=no
                wid=999
                keep-open=no
                gpu-context=x11
                demuxer-max-bytes=104857600
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        // Protected keys must remain invariant
        assertEquals("libmpv", fakeBridge.options["vo"])
        assertEquals("yes", fakeBridge.options["idle"])
        assertEquals("yes", fakeBridge.options["keep-open"])
        assertFalse(fakeBridge.options.containsKey("wid"))
        assertFalse(fakeBridge.options.containsKey("gpu-context"))

        // Unprotected custom keys must be applied
        assertEquals("104857600", fakeBridge.options["demuxer-max-bytes"])
    }

    @Test
    fun testGuiSettings_overrideMpvConfOptions_preInit() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText(
                """
                hwdec=no
                sub-auto=no
                """.trimIndent()
            )
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)
        val settings = UserSettings(defaultHwdec = "mediacodec", subAutoLoad = true)
        val settingsRepo = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = UserSettingsDataStoreFactory.createInMemory(settings, scope)
            val repo = DefaultSettingsRepository(store)
            repo.updateSettings { settings }
            repo.settings.first { it == settings }
            repo
        }

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() }, settings = settings)

        // GUI explicit settings must overwrite user config values for conflicting keys. The chosen
        // hardware mode is expanded with the copy-back variant so an init failure is not fatal.
        assertEquals("mediacodec,mediacodec-copy", fakeBridge.options["hwdec"])
        assertEquals("all", fakeBridge.options["sub-auto"])
    }

    @Test
    fun testRuntimeProperties_overrideMpvConf_duringPlayback() {
        val confFile = File(filesDir, "mpv.conf").apply {
            writeText("speed=1.5\n")
        }
        val mpvRepo = DefaultMpvConfigRepository(context = testContext)

        val engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge
        )
        engine.ensureInitialized(userOptions = runBlocking { mpvRepo.getParsedOptions() })

        assertEquals("1.5", fakeBridge.options["speed"])

        // Runtime dynamic mutation overrides pre-init option
        engine.setSpeed(2.0f)
        assertEquals(2.0, fakeBridge.properties["speed"] as Double, 0.001)

        engine.toggleHwdec()
        val targetHwdec = fakeBridge.properties["hwdec"] as String
        assertFalse(targetHwdec.isBlank())
    }
}
