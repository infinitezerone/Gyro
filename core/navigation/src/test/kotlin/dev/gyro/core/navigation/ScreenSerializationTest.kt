package dev.gyro.core.navigation

import android.net.TestUri
import android.net.Uri
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Before
    fun setUp() {
        UriSerializer.uriParser = { str -> TestUri(str) }
    }

    @After
    fun tearDown() {
        UriSerializer.uriParser = { str -> Uri.parse(str) }
    }

    @Test
    fun testScreenSerializerRegistration() {
        val serializer = Screen.serializer()
        assertNotNull("Screen sealed serializer must be generated", serializer)
    }

    @Test
    fun testSerializeAndDeserialize_home() {
        val original: Screen = Screen.Home
        val encoded = json.encodeToString(Screen.serializer(), original)
        assertTrue(encoded.contains("Home"))

        val decoded = json.decodeFromString(Screen.serializer(), encoded)
        assertEquals(Screen.Home, decoded)
    }

    @Test
    fun testSerializeAndDeserialize_playerWithUri() {
        val testUri = TestUri("content://media/external/video/media/42")
        val original: Screen = Screen.Player(testUri)
        val encoded = json.encodeToString(Screen.serializer(), original)
        assertTrue(encoded.contains("Player"))
        assertTrue(encoded.contains("content://media/external/video/media/42"))

        val decoded = json.decodeFromString(Screen.serializer(), encoded)
        assertTrue(decoded is Screen.Player)
        assertEquals(testUri.toString(), (decoded as Screen.Player).uri.toString())
    }

    @Test
    fun testSerializeAndDeserialize_allOtherDestinations() {
        val routes: List<Screen> = listOf(
            Screen.Settings,
            Screen.FolderManagement,
            Screen.MpvConfigEditor,
            Screen.GestureSettings
        )

        for (route in routes) {
            val encoded = json.encodeToString(Screen.serializer(), route)
            val decoded = json.decodeFromString(Screen.serializer(), encoded)
            assertEquals(route, decoded)
        }
    }
}
