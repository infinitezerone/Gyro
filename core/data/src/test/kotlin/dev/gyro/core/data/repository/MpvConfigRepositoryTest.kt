package dev.gyro.core.data.repository

import dev.gyro.core.model.MpvConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MpvConfigRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testCreationCreatesDefaultTemplateWhenFileMissing() {
        val configFile = File(tempFolder.root, "mpv.conf")
        assertTrue("Precondition: file must not exist", !configFile.exists())

        val repository = MpvConfigRepository(configFile, testDispatcher)

        assertTrue("File should be created on disk", configFile.exists())
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, repository.configText.value)
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, configFile.readText(Charsets.UTF_8))

        val options = repository.getParsedOptions()
        assertEquals("libmpv", options["vo"])
        assertEquals("android", options["gpu-context"])
    }

    @Test
    fun testLoadsExistingCustomConfigWithoutOverwriting() {
        val configFile = File(tempFolder.root, "mpv.conf")
        val customText = "hwdec=mediacodec\ndeband\nspeed=1.5\n"
        configFile.writeText(customText, Charsets.UTF_8)

        val repository = MpvConfigRepository(configFile, testDispatcher)

        assertEquals(customText, repository.configText.value)
        val options = repository.getParsedOptions()
        assertEquals("mediacodec", options["hwdec"])
        assertEquals("yes", options["deband"])
        assertEquals("1.5", options["speed"])
    }

    @Test
    fun testSaveConfigUpdatesDiskAndStateFlow() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        val repository = MpvConfigRepository(configFile, testDispatcher)

        val newContent = "scale=ewa_lanczos\nblend-subtitles=yes\n"
        repository.saveConfig(newContent)

        assertEquals(newContent, repository.configText.value)
        assertEquals(newContent, configFile.readText(Charsets.UTF_8))

        val options = repository.getParsedOptions()
        assertEquals("ewa_lanczos", options["scale"])
        assertEquals("yes", options["blend-subtitles"])
    }

    @Test
    fun testResetToDefaultOverwritesDiskWithTemplate() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        val repository = MpvConfigRepository(configFile, testDispatcher)

        repository.saveConfig("arbitrary-option=123")
        assertEquals("arbitrary-option=123", repository.configText.value)

        repository.resetToDefault()

        assertEquals(MpvConfig.DEFAULT_TEMPLATE, repository.configText.value)
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, configFile.readText(Charsets.UTF_8))
        assertEquals("libmpv", repository.getParsedOptions()["vo"])
    }

    @Test
    fun testGetConfigFileReturnsProvidedFile() {
        val configFile = File(tempFolder.root, "mpv.conf")
        val repository = MpvConfigRepository(configFile, testDispatcher)
        assertEquals(configFile.absolutePath, repository.getConfigFile().absolutePath)
    }
}
