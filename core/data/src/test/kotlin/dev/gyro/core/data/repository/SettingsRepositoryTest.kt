package dev.gyro.core.data.repository

import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun testDefaultSettingsWhenPreferencesEmpty() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        val current = repo.settings.value

        // General defaults
        assertTrue(current.autoResume)
        assertEquals("pip", current.backgroundPlayMode)
        assertEquals(10, current.seekStepSeconds)
        assertEquals("no", current.defaultHwdec)
        assertTrue(current.subAutoLoad)
        assertTrue(current.gesturesEnabled)

        // Gesture defaults
        val gesture = current.gestureConfig
        assertFalse(gesture.isLocked)
        assertTrue(gesture.isVolumeEnabled)
        assertTrue(gesture.isBrightnessEnabled)
        assertTrue(gesture.isSeekEnabled)
        assertTrue(gesture.isZoomEnabled)
        assertTrue(gesture.isDoubleTapSeekEnabled)
        assertTrue(gesture.isDoubleTapPlayPauseEnabled)
        assertTrue(gesture.isLongPressSpeedEnabled)
        assertFalse(gesture.swapVolumeAndBrightness)
        assertEquals(10, gesture.doubleTapSeekSeconds)
        assertEquals(2.0f, gesture.longPressSpeed, 0.001f)
    }

    @Test
    fun testExistingPreferencesLoadedCorrectly() {
        val prefs = FakeSharedPreferences(
            mapOf(
                "auto_resume" to false,
                "bg_play_mode" to "audio_only",
                "seek_step_sec" to 30,
                "default_hwdec" to "mediacodec-copy",
                "sub_auto_load" to false,
                "gestures_enabled" to false,
                "gesture_swap_vol_bright" to true,
                "gesture_long_press_speed_val" to 3.0f,
                "gesture_vol_sensitivity" to 1.5f
            )
        )

        val repo = SettingsRepository(prefs)
        val current = repo.settings.value

        assertFalse(current.autoResume)
        assertEquals("audio_only", current.backgroundPlayMode)
        assertEquals(30, current.seekStepSeconds)
        assertEquals("mediacodec-copy", current.defaultHwdec)
        assertFalse(current.subAutoLoad)
        assertFalse(current.gesturesEnabled)

        assertTrue(current.gestureConfig.swapVolumeAndBrightness)
        assertEquals(3.0f, current.gestureConfig.longPressSpeed, 0.001f)
        assertEquals(1.5f, current.gestureConfig.volumeSensitivity, 0.001f)
    }

    @Test
    fun testUpdateGestureConfigUpdatesPrefsAndStateFlow() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.updateGestureConfig { current ->
            current.copy(
                isVolumeEnabled = false,
                swapVolumeAndBrightness = true,
                doubleTapSeekSeconds = 15,
                longPressSpeed = 2.5f
            )
        }

        val updated = repo.settings.value.gestureConfig
        assertFalse(updated.isVolumeEnabled)
        assertTrue(updated.swapVolumeAndBrightness)
        assertEquals(15, updated.doubleTapSeekSeconds)
        assertEquals(2.5f, updated.longPressSpeed, 0.001f)

        // Verify backing SharedPreferences was written
        assertFalse(prefs.getBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, true))
        assertTrue(prefs.getBoolean(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, false))
        assertEquals(15, prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10))
        assertEquals(2.5f, prefs.getFloat(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED, 2.0f), 0.001f)
    }

    @Test
    fun testIndividualGestureTogglesPersistence() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.setGestureVolumeEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isVolumeEnabled)
        assertFalse(prefs.getBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, true))

        repo.setGestureSwapVolumeBrightness(true)
        assertTrue(repo.settings.value.gestureConfig.swapVolumeAndBrightness)
        assertTrue(prefs.getBoolean(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, false))

        repo.setGestureLongPressSpeed(3.0f)
        assertEquals(3.0f, repo.settings.value.gestureConfig.longPressSpeed, 0.001f)
        assertEquals(3.0f, prefs.getFloat(SettingsRepository.KEY_GESTURE_LONG_PRESS_SPEED, 2.0f), 0.001f)

        repo.setGestureBrightnessEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isBrightnessEnabled)

        repo.setGestureSeekEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isSeekEnabled)

        repo.setGestureZoomEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isZoomEnabled)

        repo.setGestureDoubleTapSeekEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isDoubleTapSeekEnabled)

        repo.setGestureDoubleTapPlayPauseEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isDoubleTapPlayPauseEnabled)

        repo.setGestureLongPressSpeedEnabled(false)
        assertFalse(repo.settings.value.gestureConfig.isLongPressSpeedEnabled)
    }

    @Test
    fun testSeekStepSecondsSynchronization() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.setSeekStepSeconds(15)
        assertEquals(15, repo.settings.value.seekStepSeconds)
        assertEquals(15, repo.settings.value.gestureConfig.doubleTapSeekSeconds)
        assertEquals(15, prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10))
        assertEquals(15, prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10))

        repo.setGestureDoubleTapSeekSeconds(30)
        assertEquals(30, repo.settings.value.seekStepSeconds)
        assertEquals(30, repo.settings.value.gestureConfig.doubleTapSeekSeconds)
    }

    @Test
    fun testResetGestureConfigToDefault() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.updateGestureConfig {
            it.copy(
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                longPressSpeed = 4.0f
            )
        }
        assertFalse(repo.settings.value.gestureConfig.isVolumeEnabled)

        repo.resetGestureConfigToDefault()
        assertEquals(PlayerGestureConfig(), repo.settings.value.gestureConfig)
    }

    @Test
    fun testGeneralSettingsUpdates() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        repo.setAutoResume(false)
        assertFalse(repo.settings.value.autoResume)
        assertFalse(prefs.getBoolean(SettingsRepository.KEY_AUTO_RESUME, true))

        repo.setBackgroundPlayMode("off")
        assertEquals("off", repo.settings.value.backgroundPlayMode)
        assertEquals("off", prefs.getString(SettingsRepository.KEY_BG_PLAY_MODE, "pip"))

        repo.setSeekStepSeconds(15)
        assertEquals(15, repo.settings.value.seekStepSeconds)
        assertEquals(15, prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10))

        repo.setDefaultHwdec("mediacodec-copy")
        assertEquals("mediacodec-copy", repo.settings.value.defaultHwdec)
        assertEquals("mediacodec-copy", prefs.getString(SettingsRepository.KEY_DEFAULT_HWDEC, ""))

        repo.setSubAutoLoad(false)
        assertFalse(repo.settings.value.subAutoLoad)
        assertFalse(prefs.getBoolean(SettingsRepository.KEY_SUB_AUTO_LOAD, true))

        repo.setGesturesEnabled(false)
        assertFalse(repo.settings.value.gesturesEnabled)
        assertFalse(prefs.getBoolean(SettingsRepository.KEY_GESTURES_ENABLED, true))
    }

    @Test
    fun testPersistenceAcrossReinstantiation() {
        val sharedPrefs = FakeSharedPreferences()
        val repo1 = SettingsRepository(sharedPrefs)

        repo1.setSeekStepSeconds(30)
        repo1.updateGestureConfig { it.copy(longPressSpeed = 3.0f, swapVolumeAndBrightness = true) }

        // Second repository sharing the exact same backing storage
        val repo2 = SettingsRepository(sharedPrefs)
        val loaded = repo2.settings.value

        assertEquals(30, loaded.seekStepSeconds)
        assertEquals(3.0f, loaded.gestureConfig.longPressSpeed, 0.001f)
        assertTrue(loaded.gestureConfig.swapVolumeAndBrightness)
    }
}
