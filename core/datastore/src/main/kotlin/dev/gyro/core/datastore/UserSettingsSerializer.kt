package dev.gyro.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.gyro.core.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

object UserSettingsSerializer : Serializer<UserSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    override val defaultValue: UserSettings = UserSettings()

    override suspend fun readFrom(input: InputStream): UserSettings {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(UserSettings.serializer(), text)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Cannot read user settings.", exception)
        }
    }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(json.encodeToString(UserSettings.serializer(), t).encodeToByteArray())
        }
    }
}
