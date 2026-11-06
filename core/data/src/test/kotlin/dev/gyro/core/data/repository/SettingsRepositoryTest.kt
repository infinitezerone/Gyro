package dev.gyro.core.data.repository

import androidx.datastore.core.DataStore
import dev.gyro.core.datastore.UserSettingsDataStoreFactory
import dev.gyro.core.model.HWDEC_HARDWARE
import dev.gyro.core.model.HWDEC_SOFTWARE
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * Behaviour of the DataStore-backed settings repository.
 *
 * These run against a real file-backed DataStore on a real IO dispatcher instead of a
 * SharedPreferences fake: the initial load, the write path and the serializer are exactly the
 * parts a fake would paper over.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newStore(scope: CoroutineScope, name: String): DataStore<UserSettings> =
        UserSettingsDataStoreFactory.createForFile(File(tempFolder.root, "$name.json"), scope)

    private fun newRepo(store: DataStore<UserSettings>, scope: CoroutineScope): SettingsRepository =
        DefaultSettingsRepository(store)

    /** The repository loads asynchronously; every read of persisted state has to wait for it. */
    private suspend fun SettingsRepository.awaitSettings(
        predicate: (UserSettings) -> Boolean,
    ): UserSettings = withTimeout(3_000) { settings.first(predicate) }

    @Test
    fun defaultsWhenStoreIsEmpty() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "defaults"), scope)

            val current = repo.settings.first()

            assertTrue(current.autoResume)
            assertEquals("pip", current.backgroundPlayMode)
            assertEquals(10, current.seekStepSeconds)
            assertEquals(HWDEC_HARDWARE, current.defaultHwdec)
            assertTrue(current.subAutoLoad)
            assertTrue(current.gesturesEnabled)

            val gesture = current.gestureConfig
            assertFalse(gesture.isLocked)
            assertTrue(gesture.isVolumeEnabled)
            assertTrue(gesture.isBrightnessEnabled)
            assertTrue(gesture.isSeekEnabled)
            assertTrue(gesture.isZoomEnabled)
            assertFalse(gesture.swapVolumeAndBrightness)
            assertEquals(10, gesture.doubleTapSeekSeconds)
            assertEquals(2.0f, gesture.longPressSpeed, 0.001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistedSettingsAreLoadedOnCreation() = runBlocking {
        val scope = newScope()
        try {
            val store = newStore(scope, "persisted")
            val persisted = UserSettings(
                autoResume = false,
                backgroundPlayMode = "audio_only",
                seekStepSeconds = 30,
                defaultHwdec = HWDEC_SOFTWARE,
                subAutoLoad = false,
                gesturesEnabled = false,
            )
            store.updateData { persisted }

            val repo = newRepo(store, scope)
            val current = repo.awaitSettings { it == persisted }

            assertFalse(current.autoResume)
            assertEquals("audio_only", current.backgroundPlayMode)
            assertEquals(30, current.seekStepSeconds)
            assertEquals(HWDEC_SOFTWARE, current.defaultHwdec)
            assertFalse(current.subAutoLoad)
            assertFalse(current.gesturesEnabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun updatesSurviveRepositoryRecreation() = runBlocking {
        val scope = newScope()
        try {
            val store = newStore(scope, "recreate")
            val first = newRepo(store, scope)
            first.setAutoResume(false)
            first.setSeekStepSeconds(25)

            // A second instance over the same store: this is what a configuration change or a
            // ViewModel recreation looks like, and it must not resurrect the defaults.
            val second = newRepo(store, scope)
            val restored = second.awaitSettings { it.seekStepSeconds == 25 }

            assertFalse(restored.autoResume)
            assertEquals(25, restored.seekStepSeconds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun gestureConfigRoundTripsThroughDisk() = runBlocking {
        val scope = newScope()
        try {
            val store = newStore(scope, "gestures")
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

            val restored = newRepo(store, scope).awaitSettings { it.gestureConfig == custom }

            assertEquals(custom, restored.gestureConfig)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun setSeekStepSecondsKeepsGestureDoubleTapInSync() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "seek_sync"), scope)

            repo.setSeekStepSeconds(15)

            val settled = repo.awaitSettings { it.seekStepSeconds == 15 }
            assertEquals(15, settled.seekStepSeconds)
            assertEquals(15, settled.gestureConfig.doubleTapSeekSeconds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resetGestureConfigRestoresDefaults() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "reset"), scope)
            repo.updateGestureConfig { it.copy(isLocked = true, longPressSpeed = 3f) }
            repo.resetGestureConfigToDefault()

            val gesture = repo.awaitSettings { it.gestureConfig == PlayerGestureConfig() }.gestureConfig
            assertFalse(gesture.isLocked)
            assertEquals(2.0f, gesture.longPressSpeed, 0.001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankHwdecIsStoredVerbatim() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "hwdec"), scope)

            repo.setDefaultHwdec("")

            assertEquals("", repo.awaitSettings { it.defaultHwdec == "" }.defaultHwdec)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun settingsFlowReflectsUpdatesImmediately() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo(newStore(scope, "flow"), scope)
            val before = repo.settings.first()

            val returned = repo.setBackgroundPlayMode("audio_only")

            assertEquals("audio_only", returned.backgroundPlayMode)
            val settled = repo.awaitSettings { it.backgroundPlayMode == "audio_only" }
            assertEquals(before.copy(backgroundPlayMode = "audio_only"), settled)
        } finally {
            scope.cancel()
        }
    }
}
