package dev.gyro.core.data.repository

import androidx.datastore.core.DataStore
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Concurrency guarantees for the DataStore-backed settings repository.
 *
 * These use a real IO dispatcher on purpose: the point is to prove that concurrent updates are
 * serialized by DataStore and by the repository's lock, instead of racing the in-memory snapshot.
 * One DataStore instance per file is a hard constraint, so recreating the repository means
 * recreating the repository, not the store.
 */
class SettingsRepositoryStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newStore(scope: CoroutineScope, name: String): DataStore<UserSettings> =
        UserSettingsDataStoreFactory.createForFile(File(tempFolder.root, "$name.json"), scope)

    private fun newRepo(store: DataStore<UserSettings>, scope: CoroutineScope): SettingsRepository =
        DefaultSettingsRepository(store)

    @Test
    fun allGestureSettingsSurviveRepositoryRecreation() = runBlocking {
        val scope = newScope()
        try {
            val store = newStore(scope, "gesture_roundtrip")

            val custom = PlayerGestureConfig(
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
                edgeExclusionPx = 120f,
                volumeSensitivity = 1.8f,
                brightnessSensitivity = 0.6f,
                longPressTimeoutMs = 600L,
                doubleTapTimeoutMs = 400L,
                singleTapDelayMs = 350L,
            )
            newRepo(store, scope).setGestureConfig(custom)

            val restored = newRepo(store, scope)
            val loaded = withTimeout(3_000) { restored.settings.first { it.gestureConfig == custom } }
            assertEquals(custom, loaded.gestureConfig)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun seekStepAndGestureDoubleTapStayInSync() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "seek_sync"), scope)

            repo.setSeekStepSeconds(25)
            val afterSeek = withTimeout(3_000) { repo.settings.first { it.seekStepSeconds == 25 } }
            assertEquals(25, afterSeek.seekStepSeconds)
            assertEquals(25, afterSeek.gestureConfig.doubleTapSeekSeconds)

            repo.setSeekStepSeconds(45)
            val afterDoubleTap = withTimeout(3_000) { repo.settings.first { it.seekStepSeconds == 45 } }
            assertEquals(45, afterDoubleTap.seekStepSeconds)
            assertEquals(45, afterDoubleTap.gestureConfig.doubleTapSeekSeconds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentDistinctFieldUpdatesAreNotLost() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "concurrent"), scope)

            val jobs = listOf(
                launch { repo.updateGestureConfig { it.copy(isVolumeEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isBrightnessEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isSeekEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isZoomEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isDoubleTapSeekEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isDoubleTapPlayPauseEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(isLongPressSpeedEnabled = false) } },
                launch { repo.updateGestureConfig { it.copy(swapVolumeAndBrightness = true) } },
            )
            jobs.joinAll()

            // Wait for the merged state instead of sampling .value: the write path is DataStore's
            // updateData, and its emission lands one dispatch later.
            val config = withTimeout(3_000) {
                repo.settings.first { state ->
                    with(state.gestureConfig) {
                        !isVolumeEnabled && !isBrightnessEnabled && !isSeekEnabled && !isZoomEnabled &&
                            !isDoubleTapSeekEnabled && !isDoubleTapPlayPauseEnabled &&
                            !isLongPressSpeedEnabled && swapVolumeAndBrightness
                    }
                }
            }.gestureConfig

            assertFalse("isVolumeEnabled", config.isVolumeEnabled)
            assertFalse("isBrightnessEnabled", config.isBrightnessEnabled)
            assertFalse("isSeekEnabled", config.isSeekEnabled)
            assertFalse("isZoomEnabled", config.isZoomEnabled)
            assertFalse("isDoubleTapSeekEnabled", config.isDoubleTapSeekEnabled)
            assertFalse("isDoubleTapPlayPauseEnabled", config.isDoubleTapPlayPauseEnabled)
            assertFalse("isLongPressSpeedEnabled", config.isLongPressSpeedEnabled)
            assertTrue("swapVolumeAndBrightness", config.swapVolumeAndBrightness)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedWritesConvergeWithoutDeadlock() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "repeated"), scope)

            val jobs = (0 until 50).map {
                launch { repo.updateGestureConfig { it.copy(isVolumeEnabled = false) } }
            }
            jobs.joinAll()

            val settled = withTimeout(3_000) {
                repo.settings.first { !it.gestureConfig.isVolumeEnabled }
            }
            assertFalse(settled.gestureConfig.isVolumeEnabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun boundarySeekStepValuesRoundTrip() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "seek_boundary"), scope)

            for (value in listOf(0, 1, 3600)) {
                repo.setSeekStepSeconds(value)
                val loaded = withTimeout(3_000) { repo.settings.first { it.seekStepSeconds == value } }
                assertEquals(value, loaded.seekStepSeconds)
                assertEquals(value, loaded.gestureConfig.doubleTapSeekSeconds)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun boundaryLongPressSpeedRoundTrips() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "speed_boundary"), scope)

            for (value in listOf(0.25f, 10f)) {
                repo.updateGestureConfig { it.copy(longPressSpeed = value) }
                val loaded = withTimeout(3_000) {
                    repo.settings.first { it.gestureConfig.longPressSpeed == value }
                }
                assertEquals(value, loaded.gestureConfig.longPressSpeed, 0.001f)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedResetConvergesToDefaults() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "repeated_reset"), scope)

            repeat(20) {
                repo.updateGestureConfig { it.copy(isLocked = true) }
                repo.resetGestureConfigToDefault()
            }

            val settled = withTimeout(3_000) {
                repo.settings.first { it.gestureConfig == PlayerGestureConfig() }
            }.gestureConfig
            assertFalse(settled.isLocked)
            assertEquals(PlayerGestureConfig(), settled)
        } finally {
            scope.cancel()
        }
    }
}
