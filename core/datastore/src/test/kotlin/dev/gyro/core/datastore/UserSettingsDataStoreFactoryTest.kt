package dev.gyro.core.datastore

import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun validFileRoundTrips() = runTest {
        val file = tempFolder.newFile("user_settings.json")
        val scope1 = newScope()
        try {
            val store = UserSettingsDataStoreFactory.createForFile(file, scope1)
            store.updateData { it.copy(autoResume = false, backgroundAudio = true) }
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }
        val scope2 = newScope()
        try {
            val read = UserSettingsDataStoreFactory.createForFile(file, scope2).data.first()
            assertEquals(false, read.autoResume)
            assertEquals(true, read.backgroundAudio)
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}