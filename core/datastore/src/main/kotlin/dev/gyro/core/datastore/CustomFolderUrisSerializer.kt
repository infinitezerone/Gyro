package dev.gyro.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Persists the SAF tree URIs the user has granted.
 *
 * A list, not a set: the ordering is irrelevant to every reader but preserving it keeps the file
 * diff-stable and avoids a conversion on each read.
 */
object CustomFolderUrisSerializer : Serializer<List<String>> {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    override val defaultValue: List<String> = emptyList()

    override suspend fun readFrom(input: InputStream): List<String> {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(serializer, text)
        } catch (exception: IllegalArgumentException) {
            throw CorruptionException("Cannot read custom folder URIs.", exception)
        }
    }

    override suspend fun writeTo(t: List<String>, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(json.encodeToString(serializer, t).encodeToByteArray())
        }
    }
}
