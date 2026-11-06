package dev.gyro.feature.settings.mpv

import dev.gyro.core.testing.fakes.FakeContext
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.model.MpvConfig
import dev.gyro.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalCoroutinesApi::class)
class MpvConfigEditorAdversarialTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun createRepository(initialText: String = MpvConfig.DEFAULT_TEMPLATE): Pair<File, MpvConfigRepository> {
        val file = File(tempFolder.root, "mpv.conf")
        file.writeText(initialText, Charsets.UTF_8)
        return file to DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)
    }

    private fun createViewModel(repository: MpvConfigRepository): MpvConfigEditorViewModel {
        return MpvConfigEditorViewModel(repository)
    }

    // =========================================================================
    // Category 1: Extreme Scale & High Payload Stress Tests
    // =========================================================================

    @Test
    fun testExtremeScale_10000Lines_parsingAndPersistence() = runTest(testDispatcher) {
        val (_, repository) = createRepository()
        val viewModel = createViewModel(repository)

        val lineCount = 12_000
        val sb = StringBuilder(lineCount * 25)
        sb.append("# Extreme stress test payload\n")
        for (i in 1..lineCount) {
            when (i % 4) {
                0 -> sb.append("setting_$i=value_$i\n")
                1 -> sb.append("# Comment line $i\n")
                2 -> sb.append("flag_$i\n")
                3 -> sb.append("no-flag_$i\n")
            }
        }
        val largeContent = sb.toString()

        val updateDuration = measureTimeMillis {
            viewModel.updateContent(largeContent)
        }
        println("UpdateContent 12k lines duration: ${updateDuration}ms")

        val state = viewModel.uiState.value
        assertEquals(largeContent, state.content)
        assertTrue(state.isModified)
        assertTrue("Line count should be at least $lineCount", state.lineCount >= lineCount)
        assertEquals(largeContent.length, state.charCount)

        val saveDuration = measureTimeMillis {
            viewModel.saveConfig()
            advanceUntilIdle()
        }
        println("SaveConfig 12k lines duration: ${saveDuration}ms")

        assertFalse(viewModel.uiState.value.isSaving)
        assertFalse(viewModel.uiState.value.isModified)
        assertEquals(largeContent, repository.loadConfig())

        val parseDuration = measureTimeMillis {
            val options = repository.getParsedOptions()
            assertTrue(options.containsKey("setting_4"))
            assertEquals("value_4", options["setting_4"])
            assertTrue(options.containsKey("flag_2"))
            assertEquals("yes", options["flag_2"])
            assertTrue(options.containsKey("flag_3"))
            assertEquals("no", options["flag_3"])
        }
        println("ParseOptions 12k lines duration: ${parseDuration}ms")
    }

    @Test
    fun testExtremeScale_MultiMegabytePayload() = runTest(testDispatcher) {
        val (_, repository) = createRepository()
        val viewModel = createViewModel(repository)

        // 5 Megabytes payload
        val targetBytes = 5 * 1024 * 1024
        val chunk = "demuxer-max-bytes=104857600\nsub-font=\"Noto Sans CJK SC\"\n"
        val repeats = targetBytes / chunk.toByteArray(Charsets.UTF_8).size + 10
        val largeSb = StringBuilder(repeats * chunk.length)
        repeat(repeats) {
            largeSb.append(chunk)
        }
        val mbContent = largeSb.toString()
        assertTrue("Content should be at least 5MB", mbContent.toByteArray(Charsets.UTF_8).size >= 5 * 1024 * 1024)

        viewModel.updateContent(mbContent)
        viewModel.saveConfig()
        advanceUntilIdle()

        assertEquals(mbContent, repository.loadConfig())
        val parsed = repository.getParsedOptions()
        assertEquals("104857600", parsed["demuxer-max-bytes"])
        assertEquals("Noto Sans CJK SC", parsed["sub-font"])
    }

    // =========================================================================
    // Category 2: Unusual Characters, Unicode, Emojis, CRLF, Quotes
    // =========================================================================

    @Test
    fun testUnusualCharacters_Unicode_Emojis_NullBytes_RTL() = runTest(testDispatcher) {
        val (_, repository) = createRepository()
        val viewModel = createViewModel(repository)

        val specialConfig = buildString {
            append("# Unicode & Multilingual Test\n")
            append("sub-font=\"思源黑体 Heavy\"\n")
            append("screenshot-template=\"スクリーンショット_%F_%p\"\n")
            append("osd-msg1=\"🎬 Gyro 4K 🚀 🔥 🍿\"\n")
            append("script-opts=\"rtl_text=مرحبا بالعالم\"\n")
            append("cyrillic-test=\"Привет мир\"\n")
            append("windows-path=\"C:\\Users\\Gyro\\AppData\\Local\\mpv.conf\"\n")
            append("math-symbols=\"scale=1920:1080:flags=lanczos+accurate_rnd\"\n")
            append("null-byte=\"embedded\u0000null\u0000byte\"\n")
        }

        viewModel.updateContent(specialConfig)
        viewModel.saveConfig()
        advanceUntilIdle()

        val saved = repository.loadConfig()
        assertEquals(specialConfig, saved)

        val options = repository.getParsedOptions()
        assertEquals("思源黑体 Heavy", options["sub-font"])
        assertEquals("スクリーンショット_%F_%p", options["screenshot-template"])
        assertEquals("🎬 Gyro 4K 🚀 🔥 🍿", options["osd-msg1"])
        assertEquals("rtl_text=مرحبا بالعالم", options["script-opts"])
        assertEquals("Привет мир", options["cyrillic-test"])
        assertEquals("C:\\Users\\Gyro\\AppData\\Local\\mpv.conf", options["windows-path"])
        assertEquals("scale=1920:1080:flags=lanczos+accurate_rnd", options["math-symbols"])
        assertEquals("embedded\u0000null\u0000byte", options["null-byte"])
    }

    @Test
    fun testLineEndings_WindowsCRLF_UnixLF_Mixed() = runTest(testDispatcher) {
        val (_, repository) = createRepository()
        val viewModel = createViewModel(repository)

        // Windows CRLF
        val crlfConfig = "vo=gpu-next\r\nhwdec=auto\r\ndeband=yes\r\n"
        viewModel.updateContent(crlfConfig)
        assertEquals(4, viewModel.uiState.value.lineCount)

        viewModel.saveConfig()
        advanceUntilIdle()

        var options = repository.getParsedOptions()
        assertEquals("gpu-next", options["vo"])
        assertEquals("auto", options["hwdec"])
        assertEquals("yes", options["deband"])

        // Test insertOption on CRLF content
        viewModel.insertOption("keep-open=yes")
        assertTrue(viewModel.uiState.value.content.endsWith("keep-open=yes\n"))

        // Mixed line endings
        val mixedConfig = "line1=val1\r\nline2=val2\nline3=val3\r\n"
        viewModel.updateContent(mixedConfig)
        assertEquals(4, viewModel.uiState.value.lineCount)
        viewModel.saveConfig()
        advanceUntilIdle()

        options = repository.getParsedOptions()
        assertEquals("val1", options["line1"])
        assertEquals("val2", options["line2"])
        assertEquals("val3", options["line3"])
    }

    @Test
    fun testSyntaxEdgeCases_Quotes_Comments_Malformed() {
        val testInput = """
            # Entire line comment
               # Indented comment
            --vo=gpu-next # Option with double leading dashes and inline comment
            hwdec=mediacodec    # Option with trailing space before inline comment
            title="Episode #1: Awakening" # Hashtag inside double quotes
            sub-title='Part #2: #NotAComment'
            unclosed-double="unclosed value # not a comment
            unclosed-single='unclosed value
            deband
            no-cache
            no-
            =invalid_leading_equals
            ====
            empty_val=
            spaced_key   =   spaced_val   
            osd-color="#FF0000"
            --keep-open=always
        """.trimIndent()

        val parsed = MpvConfig.parse(testInput)

        assertEquals("gpu-next", parsed["vo"])
        assertEquals("mediacodec", parsed["hwdec"])
        assertEquals("Episode #1: Awakening", parsed["title"])
        assertEquals("Part #2: #NotAComment", parsed["sub-title"])
        // Unclosed quotes behavior
        assertNotNull(parsed["unclosed-double"])
        assertNotNull(parsed["unclosed-single"])
        // Boolean flags
        assertEquals("yes", parsed["deband"])
        assertEquals("no", parsed["cache"])
        assertFalse(parsed.containsKey(""))
        assertFalse(parsed.containsKey("="))
        // Empty value
        assertEquals("", parsed["empty_val"])
        // Spaced key/value
        assertEquals("spaced_val", parsed["spaced_key"])
        assertEquals("always", parsed["keep-open"])

        val hexParsed = MpvConfig.parse("osd-color=#FF0000\nsub-color='#00FF00'\nosd-border-color=#80000000 # comment")
        assertEquals("#FF0000", hexParsed["osd-color"])
        assertEquals("#00FF00", hexParsed["sub-color"])
        assertEquals("#80000000", hexParsed["osd-border-color"])
    }

    // =========================================================================
    // Category 3: Race Conditions & Concurrent Mutations
    // =========================================================================

    private class ControllableDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        private val queue = mutableListOf<Runnable>()
        var isPaused = true

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (isPaused) {
                queue.add(block)
            } else {
                block.run()
            }
        }

        fun resumeAndFlush() {
            isPaused = false
            val copy = ArrayList(queue)
            queue.clear()
            copy.forEach { it.run() }
        }
    }

    @Test
    fun testConcurrentEditsWhileSaveInFlight_dirtyStateIntegrity() = runTest(testDispatcher) {
        val raceDir = tempFolder.newFolder("race1")
        val file = File(raceDir, "mpv.conf")
        file.writeText("initial=1", Charsets.UTF_8)
        val controllableIo = ControllableDispatcher()
        val repository = DefaultMpvConfigRepository(FakeContext(raceDir), controllableIo)
        val viewModel = createViewModel(repository)

        // User edits text to version 1
        viewModel.updateContent("version=1")
        assertTrue(viewModel.uiState.value.isModified)

        // Trigger save (captures "version=1" and launches coroutine)
        viewModel.saveConfig()
        // Step coroutine on Main dispatcher: sets isSaving = true, then calls saveConfig -> withContext(controllableIo)
        testScheduler.runCurrent()
        assertTrue("isSaving should be true while save is in flight", viewModel.uiState.value.isSaving)

        // User immediately continues typing before save completes!
        viewModel.updateContent("version=1\nunsaved_mutation=2")
        assertEquals("version=1\nunsaved_mutation=2", viewModel.uiState.value.content)

        // Now resume IO dispatcher to allow the save to write to file and complete
        controllableIo.resumeAndFlush()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        println("After save complete: content='${state.content}', originalContent='${state.originalContent}', isModified=${state.isModified}")

        // Empirical Observation:
        // What was persisted to repository?
        assertEquals("version=1", repository.loadConfig())
        // What is currently in the editor?
        assertEquals("version=1\nunsaved_mutation=2", state.content)

        // Original content tracks what was persisted to disk
        assertEquals("version=1", state.originalContent)
        // Dirty state must remain true because content != originalContent
        assertTrue("State must remain modified because unsaved mutations occurred during save", state.isModified)
        assertFalse("isSaving must be reset to false", state.isSaving)
    }

    @Test
    fun testCallingResetToDefaultWhileSaveInFlight() = runTest(testDispatcher) {
        val raceDir = tempFolder.newFolder("race2")
        val file = File(raceDir, "mpv.conf")
        file.writeText("custom=old", Charsets.UTF_8)
        val controllableIo = ControllableDispatcher()
        val repository = DefaultMpvConfigRepository(FakeContext(raceDir), controllableIo)
        val viewModel = createViewModel(repository)

        viewModel.updateContent("custom=new_save")
        viewModel.saveConfig()
        testScheduler.runCurrent()
        assertTrue("isSaving should be true while save is in flight", viewModel.uiState.value.isSaving)

        // Call resetToDefault while save is in flight
        viewModel.resetToDefault()
        testScheduler.runCurrent()

        // Resume IO
        controllableIo.resumeAndFlush()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        println("Final content after race: '${state.content}'")
        println("Final repo after race: '${repository.loadConfig()}'")
        // Check that repository and editor end up in a clean, consistent state
        assertEquals(repository.loadConfig(), state.content)
        assertFalse(state.isModified)
    }

    @Test
    fun testRapidSuccessiveSaveConfigCalls() = runTest(testDispatcher) {
        val (_, repository) = createRepository()
        val viewModel = createViewModel(repository)

        for (i in 1..20) {
            viewModel.updateContent("step_$i=value_$i")
            viewModel.saveConfig()
        }

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertFalse(state.isModified)
        assertEquals("step_20=value_20", state.content)
        assertEquals("step_20=value_20", repository.loadConfig())
    }

    // =========================================================================
    // Category 4: Error Paths & Disk Faults
    // =========================================================================

    @Test
    fun testSimulatedDiskFailure_gracefulHandlingAndDirtyRetention() = runTest(testDispatcher) {
        // Create repository pointing to a directory path instead of a file so writeText fails
        val notADir = tempFolder.newFile("not_a_dir")
        val repoWithFault = DefaultMpvConfigRepository(FakeContext(notADir), testDispatcher)
        val viewModel = createViewModel(repoWithFault)

        viewModel.updateContent("critical_setting=1")
        assertTrue(viewModel.uiState.value.isModified)

        viewModel.saveConfig()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("isSaving must reset to false even on error", state.isSaving)
        assertNotNull("errorMessage must be populated on failure", state.errorMessage)
        assertNull("userMessageRes must not indicate success", state.userMessageRes)
        assertTrue("isModified MUST remain true because changes failed to save!", state.isModified)
    }
}
