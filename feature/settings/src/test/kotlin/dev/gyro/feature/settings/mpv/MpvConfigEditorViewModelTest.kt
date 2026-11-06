package dev.gyro.feature.settings.mpv

import dev.gyro.core.testing.fakes.FakeContext
import dev.gyro.core.data.repository.DefaultMpvConfigRepository
import dev.gyro.core.data.repository.MpvConfigRepository
import dev.gyro.core.designsystem.R
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

@OptIn(ExperimentalCoroutinesApi::class)
class MpvConfigEditorViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private fun createRepository(initialText: String = MpvConfig.DEFAULT_TEMPLATE): MpvConfigRepository {
        val file = File(tempFolder.root, "mpv.conf")
        file.writeText(initialText, Charsets.UTF_8)
        return DefaultMpvConfigRepository(FakeContext(tempFolder.root), testDispatcher)
    }

    private fun createViewModel(repository: MpvConfigRepository): MpvConfigEditorViewModel {
        return MpvConfigEditorViewModel(repository)
    }

    @Test
    fun testInitialState_loadsFromRepository() = runTest(testDispatcher) {
        val repository = createRepository("vo=gpu-next\nhwdec=mediacodec\n")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("vo=gpu-next\nhwdec=mediacodec\n", state.content)
        assertEquals("vo=gpu-next\nhwdec=mediacodec\n", state.originalContent)
        assertFalse(state.isModified)
        assertFalse(state.isSaving)
        assertEquals(3, state.lineCount)
        assertEquals("vo=gpu-next\nhwdec=mediacodec\n".length, state.charCount)
    }

    @Test
    fun testUpdateContent_marksModifiedAndCalculatesStats() = runTest(testDispatcher) {
        val repository = createRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.updateContent("vo=gpu-next\n")

        val state = viewModel.uiState.value
        assertEquals("vo=gpu-next\n", state.content)
        assertTrue(state.isModified)
        assertEquals(2, state.lineCount)
        assertEquals(12, state.charCount)

        // Reverting to original clears dirty state
        viewModel.updateContent(MpvConfig.DEFAULT_TEMPLATE)
        assertFalse(viewModel.uiState.value.isModified)
    }

    @Test
    fun testInsertOption_appendsSnippetCleanly() = runTest(testDispatcher) {
        val repository = createRepository("vo=libmpv")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.insertOption("deband=yes")

        val state = viewModel.uiState.value
        assertEquals("vo=libmpv\ndeband=yes\n", state.content)
        assertTrue(state.isModified)
    }

    @Test
    fun testSaveConfig_persistsToRepositoryAndClearsModified() = runTest(testDispatcher) {
        val repository = createRepository("vo=libmpv")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.updateContent("vo=gpu-next\nhwdec=mediacodec")
        viewModel.saveConfig()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isModified)
        assertFalse(state.isSaving)
        assertEquals(R.string.mpv_config_saved_toast, state.userMessageRes)
        assertEquals("vo=gpu-next\nhwdec=mediacodec", repository.loadConfig())
    }

    @Test
    fun testResetToDefault_restoresTemplate() = runTest(testDispatcher) {
        val repository = createRepository("custom=123")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.resetToDefault()

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, state.content)
        assertFalse(state.isModified)
        assertEquals(R.string.mpv_config_reset_success, state.userMessageRes)
        assertEquals(MpvConfig.DEFAULT_TEMPLATE, repository.loadConfig())
    }

    @Test
    fun testDialogVisibilityToggles() {
        val repository = createRepository()
        val viewModel = createViewModel(repository)

        viewModel.showResetConfirmation(true)
        assertTrue(viewModel.uiState.value.showResetDialog)
        viewModel.showResetConfirmation(false)
        assertFalse(viewModel.uiState.value.showResetDialog)

        viewModel.showDiscardConfirmation(true)
        assertTrue(viewModel.uiState.value.showDiscardDialog)
        viewModel.showDiscardConfirmation(false)
        assertFalse(viewModel.uiState.value.showDiscardDialog)

        viewModel.showHelp(true)
        assertTrue(viewModel.uiState.value.showHelpDialog)
        viewModel.showHelp(false)
        assertFalse(viewModel.uiState.value.showHelpDialog)
    }

    @Test
    fun testDismissMessageClearsNotification() = runTest(testDispatcher) {
        val repository = createRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.updateContent("vo=gpu-next")
        viewModel.saveConfig()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.userMessageRes)
        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.userMessageRes)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testSaveConfig_concurrentEditPreservesModifiedState() = runTest(testDispatcher) {
        val repository = createRepository("initial")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.updateContent("saved_version")
        viewModel.saveConfig()

        // Modify editor text before IO finishes
        viewModel.updateContent("saved_version_with_mutations")

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("saved_version_with_mutations", state.content)
        assertEquals("saved_version", state.originalContent)
        assertTrue("isModified must remain true when content was modified during save", state.isModified)
        assertFalse(state.isSaving)
        assertEquals("saved_version", repository.loadConfig())
    }
}
