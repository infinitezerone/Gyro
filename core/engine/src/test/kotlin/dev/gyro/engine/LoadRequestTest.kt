package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.net.TestUri
import android.view.Surface
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.media.DefaultMediaResourceResolver
import dev.gyro.engine.model.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Load requests: what reaches mpv, when, and what happens to a request that never gets there.
 */
class LoadRequestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine

    @Before
    fun setUp() {
        val filesDir: File = tempFolder.newFolder()
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(
            context = testContext,
            bridge = fakeBridge,
            resolver = DefaultMediaResourceResolver(testContext)
        )
        engine.ensureInitialized()
    }

    private fun loadfileCommands(): List<List<String>> =
        fakeBridge.commands.filter { it.firstOrNull() == "loadfile" }

    @Test
    fun requestWithoutSurfaceWaitsForOne() {
        engine.load("file:///tmp/movie.mp4")

        assertTrue("nothing may reach mpv before a surface exists", loadfileCommands().isEmpty())

        engine.attachSurface(Surface(SurfaceTexture(0)))

        val loadfile = loadfileCommands().single()
        assertEquals("file:///tmp/movie.mp4", loadfile[1])
    }

    @Test
    fun resumePositionRidesTheLoadfile() {
        engine.load("file:///tmp/movie.mp4", startPositionMs = 12_000L)
        engine.attachSurface(Surface(SurfaceTexture(0)))

        val loadfile = loadfileCommands().single()
        // <url> <flags> <index> <options>: the index must be present or mpv rejects the command.
        assertEquals("0", loadfile[3])
        assertTrue(loadfile[4].startsWith("start=+12.000"))
    }

    @Test
    fun replacingAQueuedRequestIssuesOnlyTheLastOne() {
        engine.load("file:///tmp/first.mp4")
        engine.load("file:///tmp/second.mp4")
        engine.attachSurface(Surface(SurfaceTexture(0)))

        val loadfile = loadfileCommands().single()
        assertEquals("file:///tmp/second.mp4", loadfile[1])
    }

    @Test(timeout = 10_000)
    fun unresolvableContentUriEndsInError() {
        engine.loadUri(TestUri("content://media/external/video/1"))

        runBlocking {
            withTimeout(5_000) {
                engine.playbackState.first { it == PlaybackState.ERROR }
            }
        }
    }
}
