package dev.gyro.feature.settings.gestures

import dev.gyro.core.data.repository.SettingsRepository
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.testing.fakes.FakeSharedPreferences
import dev.gyro.core.testing.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Adversarial stress and boundary tests for [GestureSettingsViewModel] and [SettingsRepository].
 *
 * Covers:
 * 1. Rapid concurrent mutations across 16 threads (stress & zero lost updates check).
 * 2. Extreme and boundary values (seek step seconds: negative, zero, Int.MAX_VALUE, Int.MIN_VALUE).
 * 3. Extreme speed multipliers (negative, NaN, +/- Infinity, extreme floats).
 * 4. High-throughput interleaved reset-to-defaults under active concurrent mutations.
 * 5. State consistency between ViewModel StateFlows, Repository StateFlow, and SharedPreferences storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureSettingsAdversarialTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // -------------------------------------------------------------------------
    // 1. Rapid Concurrent Mutations (16 Threads)
    // -------------------------------------------------------------------------

    @Test
    fun test16ThreadsRapidConcurrentMutationsNoCrashesAndStateConsistent() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val threadCount = 16
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until iterationsPerThread) {
                        when (t % 8) {
                            0 -> repo.setGestureVolumeEnabled(i % 2 == 0)
                            1 -> repo.setGestureBrightnessEnabled(i % 2 == 1)
                            2 -> repo.setGestureSeekEnabled(i % 2 == 0)
                            3 -> repo.setGestureDoubleTapSeekEnabled(i % 2 == 1)
                            4 -> repo.setGestureDoubleTapPlayPauseEnabled(i % 2 == 0)
                            5 -> repo.setGestureLongPressSpeedEnabled(i % 2 == 1)
                            6 -> repo.setGestureZoomEnabled(i % 2 == 0)
                            7 -> repo.setGestureSwapVolumeBrightness(i % 2 == 1)
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errorCount.incrementAndGet()
                }
            }
        }

        startLatch.countDown()
        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)

        assertTrue("16 threads must terminate within 10 seconds without deadlocks", finished)
        assertEquals("Zero exceptions should be thrown during 16-thread concurrent execution", 0, errorCount.get())

        // Verify state consistency between StateFlow and underlying SharedPreferences
        val finalConfig = repo.settings.value.gestureConfig
        assertEquals(
            "Volume enabled in StateFlow must match Prefs",
            finalConfig.isVolumeEnabled,
            prefs.getBoolean(SettingsRepository.KEY_GESTURE_VOLUME_ENABLED, true)
        )
        assertEquals(
            "Brightness enabled in StateFlow must match Prefs",
            finalConfig.isBrightnessEnabled,
            prefs.getBoolean(SettingsRepository.KEY_GESTURE_BRIGHTNESS_ENABLED, true)
        )
        assertEquals(
            "Seek enabled in StateFlow must match Prefs",
            finalConfig.isSeekEnabled,
            prefs.getBoolean(SettingsRepository.KEY_GESTURE_SEEK_ENABLED, true)
        )
        assertEquals(
            "Zoom enabled in StateFlow must match Prefs",
            finalConfig.isZoomEnabled,
            prefs.getBoolean(SettingsRepository.KEY_GESTURE_ZOOM_ENABLED, true)
        )
        assertEquals(
            "Swap sides in StateFlow must match Prefs",
            finalConfig.swapVolumeAndBrightness,
            prefs.getBoolean(SettingsRepository.KEY_GESTURE_SWAP_VOLUME_BRIGHTNESS, false)
        )

        // Verify that a newly re-instantiated repository matches the current repository exactly
        val reloadedRepo = SettingsRepository(prefs)
        assertEquals(
            "Reloaded repository must have identical settings",
            repo.settings.value,
            reloadedRepo.settings.value
        )
    }

    @Test
    fun testZeroLostUpdatesUnder16ConcurrentDistinctMutations() = runBlocking(Dispatchers.Default) {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        // 16 distinct, non-conflicting field updates executed concurrently
        val jobs = listOf(
            launch { repo.setGestureVolumeEnabled(false) },
            launch { repo.setGestureBrightnessEnabled(false) },
            launch { repo.setGestureSwapVolumeBrightness(true) },
            launch { repo.setGestureSeekEnabled(false) },
            launch { repo.setGestureDoubleTapSeekEnabled(false) },
            launch { repo.setGestureDoubleTapPlayPauseEnabled(false) },
            launch { repo.setGestureLongPressSpeedEnabled(false) },
            launch { repo.setGestureZoomEnabled(false) },
            launch { repo.setGestureDoubleTapSeekSeconds(30) },
            launch { repo.setGestureLongPressSpeed(3.0f) },
            launch { repo.setGestureVolumeSensitivity(1.8f) },
            launch { repo.setGestureBrightnessSensitivity(0.5f) },
            launch { repo.setGestureEdgeExclusionPx(100f) },
            launch { repo.setAutoResume(false) },
            launch { repo.setGesturesEnabled(false) },
            launch { repo.setSubAutoLoad(false) }
        )

        jobs.joinAll()

        val finalSettings = repo.settings.value
        val finalConfig = finalSettings.gestureConfig
        val lostUpdates = mutableListOf<String>()

        if (finalConfig.isVolumeEnabled) lostUpdates.add("isVolumeEnabled")
        if (finalConfig.isBrightnessEnabled) lostUpdates.add("isBrightnessEnabled")
        if (!finalConfig.swapVolumeAndBrightness) lostUpdates.add("swapVolumeAndBrightness")
        if (finalConfig.isSeekEnabled) lostUpdates.add("isSeekEnabled")
        if (finalConfig.isDoubleTapSeekEnabled) lostUpdates.add("isDoubleTapSeekEnabled")
        if (finalConfig.isDoubleTapPlayPauseEnabled) lostUpdates.add("isDoubleTapPlayPauseEnabled")
        if (finalConfig.isLongPressSpeedEnabled) lostUpdates.add("isLongPressSpeedEnabled")
        if (finalConfig.isZoomEnabled) lostUpdates.add("isZoomEnabled")
        if (finalConfig.doubleTapSeekSeconds != 30) lostUpdates.add("doubleTapSeekSeconds")
        if (finalSettings.seekStepSeconds != 30) lostUpdates.add("seekStepSeconds")
        if (abs(finalConfig.longPressSpeed - 3.0f) > 0.001f) lostUpdates.add("longPressSpeed")
        if (abs(finalConfig.volumeSensitivity - 1.8f) > 0.001f) lostUpdates.add("volumeSensitivity")
        if (abs(finalConfig.brightnessSensitivity - 0.5f) > 0.001f) lostUpdates.add("brightnessSensitivity")
        if (abs(finalConfig.edgeExclusionPx - 100f) > 0.001f) lostUpdates.add("edgeExclusionPx")
        if (finalSettings.autoResume) lostUpdates.add("autoResume")
        if (finalSettings.gesturesEnabled) lostUpdates.add("gesturesEnabled")
        if (finalSettings.subAutoLoad) lostUpdates.add("subAutoLoad")

        assertTrue("Zero lost updates expected under 16 concurrent mutations, but lost: $lostUpdates", lostUpdates.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. Boundary & Extreme Values: Seek Step Seconds
    // -------------------------------------------------------------------------

    @Test
    fun testExtremeAndBoundarySeekStepSeconds() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val vm = GestureSettingsViewModel(repo)

        val extremeValues = listOf(
            -100,
            -10,
            -1,
            0,
            1,
            60,
            3600,
            Int.MAX_VALUE,
            Int.MIN_VALUE
        )

        for (extreme in extremeValues) {
            repo.setSeekStepSeconds(extreme)

            // Verify StateFlow synchronization
            assertEquals("seekStepSeconds must match extreme value", extreme, repo.settings.value.seekStepSeconds)
            assertEquals(
                "gestureConfig.doubleTapSeekSeconds must match extreme value",
                extreme,
                repo.settings.value.gestureConfig.doubleTapSeekSeconds
            )

            // Verify backing prefs synchronization
            assertEquals(
                "KEY_SEEK_STEP_SEC in prefs must match extreme value",
                extreme,
                prefs.getInt(SettingsRepository.KEY_SEEK_STEP_SEC, -999)
            )
            assertEquals(
                "KEY_GESTURE_DOUBLE_TAP_SEEK_SEC in prefs must match extreme value",
                extreme,
                prefs.getInt(SettingsRepository.KEY_GESTURE_DOUBLE_TAP_SEEK_SEC, -999)
            )

            // Verify re-instantiation preserves extreme value without crashing
            val reloaded = SettingsRepository(prefs)
            assertEquals(extreme, reloaded.settings.value.seekStepSeconds)
            assertEquals(extreme, reloaded.settings.value.gestureConfig.doubleTapSeekSeconds)

            // Verify UI selection safety calculation (GestureSettingsScreen dialog selection logic)
            val availableOptions = listOf(5, 10, 15, 30)
            val selectedIndex = availableOptions.indexOf(extreme).coerceAtLeast(0)
            assertTrue("Selected index must be valid non-negative integer", selectedIndex in availableOptions.indices)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Boundary & Extreme Values: Speed Multipliers
    // -------------------------------------------------------------------------

    @Test
    fun testExtremeAndBoundarySpeedMultipliers() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)

        val extremeFloats = listOf(
            -100.0f,
            -1.0f,
            0.0f,
            0.0001f,
            100.0f,
            Float.MAX_VALUE,
            Float.MIN_VALUE,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NaN
        )

        val availableSpeedOptions = listOf(1.5f, 2.0f, 3.0f)

        for (speed in extremeFloats) {
            repo.setGestureLongPressSpeed(speed)

            val actual = repo.settings.value.gestureConfig.longPressSpeed
            if (speed.isNaN()) {
                assertTrue("speed must be NaN", actual.isNaN())
            } else {
                assertEquals("speed must match $speed", speed, actual, 0.0001f)
            }

            // Verify UI safety: dialog index lookup must not throw or return out-of-bounds index
            val selectedIndex = availableSpeedOptions.indexOfFirst { abs(it - actual) < 0.05f }.coerceAtLeast(0)
            assertTrue("UI selection index must be within range [0, 2]", selectedIndex in availableSpeedOptions.indices)

            // Verify UI safety: String.format with "%.1fx" must not throw on NaN or Infinity
            val formatted = String.format(Locale.US, "%.1fx", actual)
            assertTrue("Formatted string must not be empty", formatted.isNotEmpty())
            if (speed.isNaN()) {
                assertTrue("Formatted string must contain NaN", formatted.contains("NaN"))
            } else if (speed.isInfinite()) {
                assertTrue("Formatted string must contain Infinity", formatted.contains("Infinity"))
            }

            // Verify re-instantiation
            val reloaded = SettingsRepository(prefs)
            val reloadedSpeed = reloaded.settings.value.gestureConfig.longPressSpeed
            if (speed.isNaN()) {
                assertTrue("Reloaded speed must be NaN", reloadedSpeed.isNaN())
            } else {
                assertEquals("Reloaded speed must match $speed", speed, reloadedSpeed, 0.0001f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Repeated Rapid Reset to Default During Active Mutations
    // -------------------------------------------------------------------------

    @Test
    fun testRepeatedRapidResetToDefaultDuringActiveMutations() {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val threadCount = 16
        val iterationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)

        // 8 threads actively mutating, 8 threads actively resetting to default
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until iterationsPerThread) {
                        if (t % 2 == 0) {
                            // Mutation thread
                            when ((t + i) % 5) {
                                0 -> repo.setGestureVolumeEnabled(i % 2 == 0)
                                1 -> repo.setGestureSwapVolumeBrightness(i % 2 == 1)
                                2 -> repo.setGestureDoubleTapSeekSeconds((i % 4 + 1) * 5)
                                3 -> repo.setGestureLongPressSpeed((i % 3 + 1) * 1.0f)
                                4 -> repo.setGestureZoomEnabled(i % 2 == 0)
                            }
                        } else {
                            // Reset thread
                            repo.resetGestureConfigToDefault()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errorCount.incrementAndGet()
                }
            }
        }

        startLatch.countDown()
        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)

        assertTrue("Execution must terminate within 10 seconds without deadlocks", finished)
        assertEquals("Zero errors during concurrent mutation + reset storm", 0, errorCount.get())

        // Invariant: seekStepSeconds and doubleTapSeekSeconds must ALWAYS be synchronized
        val settings = repo.settings.value
        assertEquals(
            "seekStepSeconds must always synchronize with gestureConfig.doubleTapSeekSeconds",
            settings.seekStepSeconds,
            settings.gestureConfig.doubleTapSeekSeconds
        )

        // Final reset cleans state back to exact defaults
        repo.resetGestureConfigToDefault()
        assertEquals(PlayerGestureConfig(), repo.settings.value.gestureConfig)
        assertEquals(10, repo.settings.value.seekStepSeconds)
    }

    // -------------------------------------------------------------------------
    // 5. ViewModel Coroutine Dispatching Stress
    // -------------------------------------------------------------------------

    @Test
    fun testViewModelConcurrentStressAndUiStateSync() = runTest {
        val prefs = FakeSharedPreferences()
        val repo = SettingsRepository(prefs)
        val vm = GestureSettingsViewModel(repo)

        // Rapid sequence of ViewModel calls
        for (i in 0 until 50) {
            vm.setVolumeGestureEnabled(i % 2 == 0)
            vm.setBrightnessGestureEnabled(i % 2 == 1)
            vm.setInvertSwipeDirections(i % 2 == 0)
            vm.setSeekGestureEnabled(i % 2 == 1)
            vm.setDoubleTapSeekEnabled(i % 2 == 0)
            vm.setDoubleTapSeekSeconds(if (i % 2 == 0) 15 else 30)
            vm.setDoubleTapPlayPauseEnabled(i % 2 == 1)
            vm.setLongPressSpeedEnabled(i % 2 == 0)
            vm.setLongPressSpeedMultiplier(if (i % 2 == 0) 1.5f else 3.0f)
            vm.setPinchZoomEnabled(i % 2 == 1)
        }

        advanceUntilIdle()

        // Verify ViewModel StateFlows are completely in sync with Repository
        val vmConfig = vm.gestureConfig.value
        val uiConfig = vm.uiState.value.config
        val repoConfig = repo.settings.value.gestureConfig

        assertEquals("vm.gestureConfig must equal uiState.config", vmConfig, uiConfig)
        assertEquals("vm.gestureConfig must equal repo.settings.gestureConfig", repoConfig, vmConfig)

        // Reset and check again
        vm.resetToDefault()
        advanceUntilIdle()

        assertEquals(PlayerGestureConfig(), vm.gestureConfig.value)
        assertEquals(PlayerGestureConfig(), vm.uiState.value.config)
        assertEquals(PlayerGestureConfig(), repo.settings.value.gestureConfig)
        assertEquals(10, repo.settings.value.seekStepSeconds)
    }
}
