package dev.gyro.core.datastore

import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserSettingsDataStoreFactoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun corruptFileRecoversToDefaultsInsteadOfCrashing() = runTest {
        val file = tempFolder.newFile("user_settings.json")
        file.writeText("{ this is not valid json")
        val scope = newScope()
        try {
            val store = UserSettingsDataStoreFactory.createForFile(file, scope)
            assertEquals(UserSettings(), store.data.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun validFileRoundTrips() = runTest {
        val file = tempFolder.newFile("user_settings.json")
        val scope = newScope()
        try {
            val store = UserSettingsDataStoreFactory.createForFile(file, scope)
            store.updateData { it.copy(autoResume = false, backgroundAudio = true) }
            val read = UserSettingsDataStoreFactory.createForFile(file, scope).data.first()
            assertEquals(false, read.autoResume)
            assertEquals(true, read.backgroundAudio)
        } finally {
            scope.cancel()
        }
    }
}