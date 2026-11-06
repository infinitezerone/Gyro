package dev.gyro.core.data.repository

import dev.gyro.core.testing.fakes.FakeContext
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
    fun testCreationCreatesDefaultTemplateWhenFileMissing() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        assertTrue("Precondition: file must not exist", !configFile.exists())

        val repository = DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)

        val loaded = repository.loadConfig()
        assertTrue("File should be created on disk", configFile.exists())
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, loaded)
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, configFile.readText(Charsets.UTF_8))

        val options = repository.getParsedOptions()
        assertEquals("libmpv", options["vo"])
        assertEquals("android", options["gpu-context"])
    }

    @Test
    fun testLoadsExistingCustomConfigWithoutOverwriting() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        val customText = "hwdec=mediacodec\ndeband\nspeed=1.5\n"
        configFile.writeText(customText, Charsets.UTF_8)

        val repository = DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)

        assertEquals(customText, repository.loadConfig())
        val options = repository.getParsedOptions()
        assertEquals("mediacodec", options["hwdec"])
        assertEquals("yes", options["deband"])
        assertEquals("1.5", options["speed"])
    }

    @Test
    fun testSaveConfigUpdatesDisk() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        val repository = DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)

        val newContent = "scale=ewa_lanczos\nblend-subtitles=yes\n"
        repository.saveConfig(newContent)

        assertEquals(newContent, repository.loadConfig())
        assertEquals(newContent, configFile.readText(Charsets.UTF_8))

        val options = repository.getParsedOptions()
        assertEquals("ewa_lanczos", options["scale"])
        assertEquals("yes", options["blend-subtitles"])
    }

    @Test
    fun testResetToDefaultOverwritesDiskWithTemplate() = runTest(testDispatcher) {
        val configFile = File(tempFolder.root, "mpv.conf")
        val repository = DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)

        repository.saveConfig("arbitrary-option=123")
        assertEquals("arbitrary-option=123", repository.loadConfig())

        val resetResult = repository.resetToDefault()

        assertEquals(MpvConfig.DEFAULT_TEMPLATE, resetResult)
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, repository.loadConfig())
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, configFile.readText(Charsets.UTF_8))
        assertEquals("libmpv", repository.getParsedOptions()["vo"])
    }
}
