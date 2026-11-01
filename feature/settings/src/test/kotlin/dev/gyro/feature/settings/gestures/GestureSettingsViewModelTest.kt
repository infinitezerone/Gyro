package dev.gyro.feature.settings.gestures

import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun createViewModel(
        initialPrefs: Map<String, Any?> = emptyMap()
    ): Pair<GestureSettingsViewModel, SettingsRepository> {
        val prefs = FakeSharedPreferences(initialPrefs)
        val repo = SettingsRepository(prefs)
        val vm = GestureSettingsViewModel(repo)
        return Pair(vm, repo)
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
        val (vm, _) = createViewModel(
            mapOf(
                SettingsRepository.KEY_GESTURE_VOLUME_ENABLED to false,
                SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS to true,
                SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC to 15,
                SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED to 3.0f
            )
        )
        val config = vm.gestureConfig.value

        assertFalse(config.isVolumeEnabled)
        assertTrue(config.swapVolumeAndBrightness)
        assertEquals(15, config.doubleTapSeekSeconds)
        assertEquals(3.0f, config.longPressSpeed, 0.001f)
    }

    @Test
    fun testVerticalGestureToggles() = runTest {
        val (vm, repo) = createViewModel()

        vm.setVolumeGestureEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isVolumeEnabled)
        assertFalse(repo.settings.value.gestureConfig.isVolumeEnabled)

        vm.setBrightnessGestureEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isBrightnessEnabled)
        assertFalse(repo.settings.value.gestureConfig.isBrightnessEnabled)

        vm.setInvertSwipeDirections(true)
        advanceUntilIdle()
        assertTrue(vm.gestureConfig.value.swapVolumeAndBrightness)
        assertTrue(repo.settings.value.gestureConfig.swapVolumeAndBrightness)
    }

    @Test
    fun testHorizontalAndSeekingGestures() = runTest {
        val (vm, repo) = createViewModel()

        vm.setSeekGestureEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isSeekEnabled)
        assertFalse(repo.settings.value.gestureConfig.isSeekEnabled)

        vm.setDoubleTapSeekEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isDoubleTapSeekEnabled)
        assertFalse(repo.settings.value.gestureConfig.isDoubleTapSeekEnabled)

        // Verifies synchronization between double tap seek seconds and seekStepSeconds
        vm.setDoubleTapSeekSeconds(30)
        advanceUntilIdle()
        assertEquals(30, vm.gestureConfig.value.doubleTapSeekSeconds)
        assertEquals(30, repo.settings.value.seekStepSeconds)
    }

    @Test
    fun testPlaybackAndAdvancedControls() = runTest {
        val (vm, repo) = createViewModel()

        vm.setDoubleTapPlayPauseEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isDoubleTapPlayPauseEnabled)
        assertFalse(repo.settings.value.gestureConfig.isDoubleTapPlayPauseEnabled)

        vm.setLongPressSpeedEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isLongPressSpeedEnabled)
        assertFalse(repo.settings.value.gestureConfig.isLongPressSpeedEnabled)

        vm.setLongPressSpeedMultiplier(3.0f)
        advanceUntilIdle()
        assertEquals(3.0f, vm.gestureConfig.value.longPressSpeed, 0.001f)
        assertEquals(3.0f, repo.settings.value.gestureConfig.longPressSpeed, 0.001f)

        vm.setPinchZoomEnabled(false)
        advanceUntilIdle()
        assertFalse(vm.gestureConfig.value.isZoomEnabled)
        assertFalse(repo.settings.value.gestureConfig.isZoomEnabled)
    }

    @Test
    fun testResetToDefault() = runTest {
        val (vm, repo) = createViewModel()

        vm.setVolumeGestureEnabled(false)
        vm.setInvertSwipeDirections(true)
        vm.setDoubleTapSeekSeconds(30)
        vm.setLongPressSpeedMultiplier(1.5f)
        advanceUntilIdle()

        vm.resetToDefault()
        advanceUntilIdle()

        val resetConfig = vm.gestureConfig.value
        assertEquals(PlayerGestureConfig(), resetConfig)
        assertEquals(PlayerGestureConfig(), repo.settings.value.gestureConfig)
    }
}
