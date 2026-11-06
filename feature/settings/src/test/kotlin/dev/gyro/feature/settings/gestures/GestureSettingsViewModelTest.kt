package dev.gyro.feature.settings.gestures

import dev.gyro.core.data.repository.DefaultSettingsRepository
import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The repository exposes a cold Flow over DataStore, so nothing here can sample `.value` or pump a
 * virtual clock: every assertion waits for the emission it depends on, on real dispatchers.
 */
class GestureSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun createViewModel(
        initial: UserSettings = UserSettings()
    ): Pair<GestureSettingsViewModel, SettingsRepository> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        val store = UserSettingsDataStoreFactory.createInMemory(initial, scope)
        val repo = DefaultSettingsRepository(store)
        // Seed before the ViewModel starts collecting, so the first state it publishes is the
        // value under test rather than the default.
        runBlocking {
            repo.updateSettings { initial }
            repo.settings.first { it == initial }
        }
        return Pair(GestureSettingsViewModel(repo), repo)
    }

    private fun currentSettings(repo: SettingsRepository): UserSettings =
        runBlocking { repo.settings.first() }

    /** Waits until both the ViewModel state and the repository agree on [expected]. */
    private fun awaitGesture(
        vm: GestureSettingsViewModel,
        repo: SettingsRepository,
        expected: (PlayerGestureConfig) -> Boolean
    ) {
        runBlocking {
            withTimeout(3_000) { vm.gestureConfig.first(expected) }
            withTimeout(3_000) { repo.settings.first { expected(it.gestureConfig) } }
        }
    }

    @Test
    fun testInitialLoadExposesDefaultGestureConfig() {
        val (vm, _) = createViewModel()
        val config = vm.gestureConfig.value

        assertTrue(config.isVolumeEnabled)
        assertTrue(config.isBrightnessEnabled)
        assertTrue(config.isSeekEnabled)
        assertTrue(config.isDoubleTapSeekEnabled)
        assertTrue(config.isDoubleTapPlayPauseEnabled)
        assertTrue(config.isLongPressSpeedEnabled)
        assertTrue(config.isZoomEnabled)
        assertFalse(config.swapVolumeAndBrightness)
        assertEquals(10, config.doubleTapSeekSeconds)
        assertEquals(2.0f, config.longPressSpeed, 0.001f)
        assertEquals(listOf(5, 10, 15, 30), vm.uiState.value.availableSeekSteps)
        assertEquals(listOf(1.5f, 2.0f, 3.0f), vm.uiState.value.availableSpeedMultipliers)
    }

    @Test
    fun testInitialLoadExposesCustomPreferences() {
        val (vm, repo) = createViewModel(
            UserSettings(
                seekStepSeconds = 15,
                gestureConfig = PlayerGestureConfig(
                    isVolumeEnabled = false,
                    swapVolumeAndBrightness = true,
                    doubleTapSeekSeconds = 15,
                    longPressSpeed = 3.0f,
                )
            )
        )
        awaitGesture(vm, repo) { it.doubleTapSeekSeconds == 15 && !it.isVolumeEnabled }

        val config = vm.gestureConfig.value
        assertFalse(config.isVolumeEnabled)
        assertTrue(config.swapVolumeAndBrightness)
        assertEquals(15, config.doubleTapSeekSeconds)
        assertEquals(3.0f, config.longPressSpeed, 0.001f)
    }

    @Test
    fun testVerticalGestureToggles() {
        val (vm, repo) = createViewModel()

        vm.setVolumeGestureEnabled(false)
        awaitGesture(vm, repo) { !it.isVolumeEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isVolumeEnabled)

        vm.setBrightnessGestureEnabled(false)
        awaitGesture(vm, repo) { !it.isBrightnessEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isBrightnessEnabled)

        vm.setInvertSwipeDirections(true)
        awaitGesture(vm, repo) { it.swapVolumeAndBrightness }
        assertTrue(currentSettings(repo).gestureConfig.swapVolumeAndBrightness)
    }

    @Test
    fun testHorizontalAndSeekingGestures() {
        val (vm, repo) = createViewModel()

        vm.setSeekGestureEnabled(false)
        awaitGesture(vm, repo) { !it.isSeekEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isSeekEnabled)

        vm.setDoubleTapSeekEnabled(false)
        awaitGesture(vm, repo) { !it.isDoubleTapSeekEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isDoubleTapSeekEnabled)

        // doubleTapSeekSeconds and seekStepSeconds are kept in sync by the repository.
        vm.setDoubleTapSeekSeconds(30)
        awaitGesture(vm, repo) { it.doubleTapSeekSeconds == 30 }
        assertEquals(30, vm.gestureConfig.value.doubleTapSeekSeconds)
        assertEquals(30, currentSettings(repo).seekStepSeconds)
    }

    @Test
    fun testPlaybackAndAdvancedControls() {
        val (vm, repo) = createViewModel()

        vm.setDoubleTapPlayPauseEnabled(false)
        awaitGesture(vm, repo) { !it.isDoubleTapPlayPauseEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isDoubleTapPlayPauseEnabled)

        vm.setLongPressSpeedEnabled(false)
        awaitGesture(vm, repo) { !it.isLongPressSpeedEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isLongPressSpeedEnabled)

        vm.setLongPressSpeedMultiplier(3.0f)
        awaitGesture(vm, repo) { it.longPressSpeed == 3.0f }
        assertEquals(3.0f, currentSettings(repo).gestureConfig.longPressSpeed, 0.001f)

        vm.setPinchZoomEnabled(false)
        awaitGesture(vm, repo) { !it.isZoomEnabled }
        assertFalse(currentSettings(repo).gestureConfig.isZoomEnabled)
    }

    @Test
    fun testResetToDefault() {
        val (vm, repo) = createViewModel()

        vm.setVolumeGestureEnabled(false)
        awaitGesture(vm, repo) { !it.isVolumeEnabled }
        vm.setDoubleTapSeekSeconds(30)
        awaitGesture(vm, repo) { it.doubleTapSeekSeconds == 30 }

        vm.resetToDefault()
        awaitGesture(vm, repo) { it == PlayerGestureConfig() }

        assertEquals(PlayerGestureConfig(), vm.gestureConfig.value)
        assertEquals(PlayerGestureConfig(), currentSettings(repo).gestureConfig)
    }
}
