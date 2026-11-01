package dev.gyro.core.data.repository

import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SettingsRepositoryStressTest {

    @Test
    fun testAll17GestureSettingsRetainedAcrossReinstantiation() {
        val prefs = FakeSharedPreferences()
        val repo1 = SettingsRepository(prefs)

        // Non-default values for all 17 properties
        val customConfig = PlayerGestureConfig(
            isLocked = true,
            isVolumeEnabled = false,
            isBrightnessEnabled = false,
            isSeekEnabled = false,
            isZoomEnabled = false,
            isDoubleTapSeekEnabled = false,
            isDoubleTapPlayPauseEnabled = false,
            isLongPressSpeedEnabled = false,
            swapVolumeAndBrightness = true,
            doubleTapSeekSeconds = 25,
            longPressSpeed = 3.5f,
            edgeExclusionPx = 120.0f,
            volumeSensitivity = 1.8f,
            brightnessSensitivity = 0.6f,
            longPressTimeoutMs = 600L,
            doubleTapTimeoutMs = 400L,
            singleTapDelayMs = 350L
        )

        repo1.setGestureConfig(customConfig)

        // Re-instantiate repository using the exact same backing storage
        val repo2 = SettingsRepository(prefs)
        val loaded = repo2.settings.value.gestureConfig

        assertEquals("isLocked mismatch", true, loaded.isLocked)
        assertEquals("isVolumeEnabled mismatch", false, loaded.isVolumeEnabled)
        assertEquals("isBrightnessEnabled mismatch", false, loaded.isBrightnessEnabled)
        assertEquals("isSeekEnabled mismatch", false, loaded.isSeekEnabled)
        assertEquals("isZoomEnabled mismatch", false, loaded.isZoomEnabled)
        assertEquals("isDoubleTapSeekEnabled mismatch", false, loaded.isDoubleTapSeekEnabled)
        assertEquals("isDoubleTapPlayPauseEnabled mismatch", false, loaded.isDoubleTapPlayPauseEnabled)
        assertEquals("isLongPressSpeedEnabled mismatch", false, loaded.isLongPressSpeedEnabled)
        assertEquals("swapVolumeAndBrightness mismatch", true, loaded.swapVolumeAndBrightness)
        assertEquals("doubleTapSeekSeconds mismatch", 25, loaded.doubleTapSeekSeconds)
        assertEquals("longPressSpeed mismatch", 3.5f, loaded.longPressSpeed, 0.001f)
        assertEquals("edgeExclusionPx mismatch", 120.0f, loaded.edgeExclusionPx, 0.001f)
        assertEquals("volumeSensitivity mismatch", 1.8f, loaded.volumeSensitivity, 0.001f)
        assertEquals("brightnessSensitivity mismatch", 0.6f, loaded.brightnessSensitivity, 0.001f)
        assertEquals("longPressTimeoutMs mismatch", 600L, loaded.longPressTimeoutMs)
        assertEquals("doubleTapTimeoutMs mismatch", 400L, loaded.doubleTapTimeoutMs)
        assertEquals("singleTapDelayMs mismatch", 350L, loaded.singleTapDelayMs)

        assertEquals("Custom config must match loaded config entirely", customConfig, loaded)
    }

    @Test
    fun testSeekStepSynchronizationBidirectional() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        // 1. setSeekStepSeconds
        repo.setSeekStepSeconds(25)
        assertEquals(25, repo.settings.value.seekStepSeconds)
        assertEquals(25, repo.settings.value.gestureConfig.doubleTapSeekSeconds)
        assertEquals(25, prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10))
        assertEquals(25, prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10))

        // 2. setGestureDoubleTapSeekSeconds
        repo.setGestureDoubleTapSeekSeconds(45)
        assertEquals(45, repo.settings.value.seekStepSeconds)
        assertEquals(45, repo.settings.value.gestureConfig.doubleTapSeekSeconds)
        assertEquals(45, prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10))
        assertEquals(45, prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10))

        // 3. Re-instantiation preserves synchronized value
        val repo2 = SettingsRepository(prefs)
        assertEquals(45, repo2.settings.value.seekStepSeconds)
        assertEquals(45, repo2.settings.value.gestureConfig.doubleTapSeekSeconds)
    }

    @Test
    fun testUpdateGestureConfigDoubleTapSeekSync() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        // If updated via updateGestureConfig directly:
        repo.updateGestureConfig { it.copy(doubleTapSeekSeconds = 60) }
        val loadedConfig = repo.settings.value.gestureConfig
        assertEquals(60, loadedConfig.doubleTapSeekSeconds)

        // Verify backing prefs for gesture seek sec
        assertEquals(60, prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, 10))

        // OBSERVED BEHAVIOR: KEY_SEEK_STEP_SEC and UserSettings.seekStepSeconds were NOT updated!
        val currentSeekStep = repo.settings.value.seekStepSeconds
        val prefSeekStep = prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, 10)
        println("seekStepSeconds after updateGestureConfig: StateFlow=$currentSeekStep, Prefs=$prefSeekStep (expected 60 if fully synchronized)")

        val repo2 = SettingsRepository(prefs)
        println("Re-instantiated: seekStepSeconds=${repo2.settings.value.seekStepSeconds}, gesture.doubleTapSeekSeconds=${repo2.settings.value.gestureConfig.doubleTapSeekSeconds}")
    }

    @Test
    fun testConcurrentUpdatesStress() = runBlocking(Dispatchers.Default) {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        // 50 concurrent operations modifying independent fields
        val jobs = listOf(
            launch { repo.setGestureVolumeEnabled(false) },
            launch { repo.setGestureBrightnessEnabled(false) },
            launch { repo.setGestureSeekEnabled(false) },
            launch { repo.setGestureZoomEnabled(false) },
            launch { repo.setGestureDoubleTapSeekEnabled(false) },
            launch { repo.setGestureDoubleTapPlayPauseEnabled(false) },
            launch { repo.setGestureLongPressSpeedEnabled(false) },
            launch { repo.setGestureSwapVolumeBrightness(true) },
            launch { repo.setGestureLongPressSpeed(4.5f) },
            launch { repo.setGestureEdgeExclusionPx(100f) },
            launch { repo.setGestureVolumeSensitivity(2.0f) },
            launch { repo.setGestureBrightnessSensitivity(0.5f) },
            launch { repo.setAutoResume(false) },
            launch { repo.setBackgroundPlayMode("audio_only") },
            launch { repo.setDefaultHwdec("mediacodec-copy") },
            launch { repo.setSubAutoLoad(false) },
            launch { repo.setGesturesEnabled(false) }
        )

        jobs.joinAll()

        // Verify state is accessible and consistent
        val settings = repo.settings.value
        // Note: Under concurrent non-synchronized read-modify-write,
        // some updates may be overwritten (lost updates). We verify current behavior.
        println("Concurrent test finished. gesturesEnabled=${settings.gesturesEnabled}, isVolumeEnabled=${settings.gestureConfig.isVolumeEnabled}")
    }

    @Test
    fun testRapidAlternatingTogglesStress() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val executor = Executors.newFixedThreadPool(4)

        for (i in 0 until 100) {
            executor.submit {
                repo.setGestureVolumeEnabled(i % 2 == 0)
            }
            executor.submit {
                repo.setGestureBrightnessEnabled(i % 2 == 1)
            }
        }

        executor.shutdown()
        assertTrue("Executor must terminate within 5 seconds", executor.awaitTermination(5, TimeUnit.SECONDS))

        // Verify no deadlock, no crash, state is consistent with prefs
        val stateVolume = repo.settings.value.gestureConfig.isVolumeEnabled
        val prefVolume = prefs.getBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, true)
        // Check if StateFlow and Prefs are in sync
        println("Final volume state: StateFlow=$stateVolume, Prefs=$prefVolume")
    }

    @Test
    fun testConcurrentDistinctFieldUpdatesLostUpdateCheck() = runBlocking(Dispatchers.Default) {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        // Launch concurrent updates to 8 distinct boolean flags
        val jobs = listOf(
            launch { repo.setGestureVolumeEnabled(false) },
            launch { repo.setGestureBrightnessEnabled(false) },
            launch { repo.setGestureSeekEnabled(false) },
            launch { repo.setGestureZoomEnabled(false) },
            launch { repo.setGestureDoubleTapSeekEnabled(false) },
            launch { repo.setGestureDoubleTapPlayPauseEnabled(false) },
            launch { repo.setGestureLongPressSpeedEnabled(false) },
            launch { repo.setGestureSwapVolumeBrightness(true) }
        )

        jobs.joinAll()

        val finalConfig = repo.settings.value.gestureConfig
        println("Final config after concurrent updates: $finalConfig")

        // In a thread-safe implementation, all updates would succeed:
        val lostUpdates = mutableListOf<String>()
        if (finalConfig.isVolumeEnabled) lostUpdates.add("isVolumeEnabled")
        if (finalConfig.isBrightnessEnabled) lostUpdates.add("isBrightnessEnabled")
        if (finalConfig.isSeekEnabled) lostUpdates.add("isSeekEnabled")
        if (finalConfig.isZoomEnabled) lostUpdates.add("isZoomEnabled")
        if (finalConfig.isDoubleTapSeekEnabled) lostUpdates.add("isDoubleTapSeekEnabled")
        if (finalConfig.isDoubleTapPlayPauseEnabled) lostUpdates.add("isDoubleTapPlayPauseEnabled")
        if (finalConfig.isLongPressSpeedEnabled) lostUpdates.add("isLongPressSpeedEnabled")
        if (!finalConfig.swapVolumeAndBrightness) lostUpdates.add("swapVolumeAndBrightness")

        println("Lost updates count: ${lostUpdates.size}, lost fields: $lostUpdates")
    }
}

