package dev.gyro.engine

import android.content.Context
import android.content.ContextWrapper
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GyroPlayerEngineAudiovisualTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("engine_audiovisual")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()
    }

    // =========================================================================
    // R3: Chapter Parsing & Navigation Tests
    // =========================================================================

    @Test
    fun testInitialization_setsVolumeMaxAndObservesChaptersAndFilters() {
        assertEquals("200.0", fakeBridge.options["volume-max"])
        assertTrue(fakeBridge.isPropertyObserved("chapter-list"))
        assertTrue(fakeBridge.isPropertyObserved("chapter"))
        assertTrue(fakeBridge.isPropertyObserved("deband"))
        assertTrue(fakeBridge.isPropertyObserved("brightness"))
        assertTrue(fakeBridge.isPropertyObserved("contrast"))
        assertTrue(fakeBridge.isPropertyObserved("saturation"))
    }

    @Test
    fun testChapterParsing_multipleChapters() {
        fakeBridge.properties["chapter-list/count"] = 3L
        fakeBridge.properties["chapter-list/0/title"] = "Prologue"
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.properties["chapter-list/1/title"] = "The Journey"
        fakeBridge.properties["chapter-list/1/time"] = 125.5
        fakeBridge.properties["chapter-list/2/title"] = "Credits"
        fakeBridge.properties["chapter-list/2/time"] = 600.0
        fakeBridge.properties["chapter"] = 0L

        fakeBridge.emitProperty("chapter-list")

        val chapters = engine.trackManager.chapters.value
        assertEquals(3, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals(0L, chapters[0].timeMs)
        assertEquals("The Journey", chapters[1].title)
        assertEquals(125500L, chapters[1].timeMs)
        assertEquals("Credits", chapters[2].title)
        assertEquals(600000L, chapters[2].timeMs)
        assertEquals(0, engine.trackManager.currentChapter.value)
    }

    @Test
    fun testChapterParsing_emptyChapters() {
        fakeBridge.properties["chapter-list/count"] = 0L
        fakeBridge.emitProperty("chapter-list")

        assertTrue(engine.trackManager.chapters.value.isEmpty())
        assertEquals(-1, engine.trackManager.currentChapter.value)
    }

    @Test
    fun testChapterParsing_nullOrBlankTitleFallback() {
        fakeBridge.properties["chapter-list/count"] = 2L
        fakeBridge.properties["chapter-list/0/title"] = null
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.properties["chapter-list/1/title"] = "   "
        fakeBridge.properties["chapter-list/1/time"] = 60.0

        fakeBridge.emitProperty("chapter-list")

        val chapters = engine.trackManager.chapters.value
        assertEquals(2, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
    }

    @Test
    fun testChapterNavigation_seekToChapterValid() {
        fakeBridge.properties["chapter-list/count"] = 2L
        fakeBridge.properties["chapter-list/0/title"] = "Ch1"
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.properties["chapter-list/1/title"] = "Ch2"
        fakeBridge.properties["chapter-list/1/time"] = 120.0
        fakeBridge.emitProperty("chapter-list")

        // Simulate file loaded
        fakeBridge.emitFileLoaded("Test")

        engine.seekToChapter(1)

        assertEquals(1L, fakeBridge.properties["chapter"])
        assertEquals(1, engine.trackManager.currentChapter.value)
    }

    @Test
    fun testChapterNavigation_seekToChapterOutOfBoundsDoesNothing() {
        fakeBridge.properties["chapter-list/count"] = 2L
        fakeBridge.properties["chapter-list/0/title"] = "Ch1"
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.properties["chapter-list/1/title"] = "Ch2"
        fakeBridge.properties["chapter-list/1/time"] = 120.0
        fakeBridge.emitProperty("chapter-list")
        fakeBridge.emitFileLoaded("Test")

        fakeBridge.properties.remove("chapter")

        engine.seekToChapter(5)
        assertFalse(fakeBridge.properties.containsKey("chapter"))

        engine.seekToChapter(-1)
        assertFalse(fakeBridge.properties.containsKey("chapter"))
    }

    @Test
    fun testChapterReset_onEndFile() {
        fakeBridge.properties["chapter-list/count"] = 1L
        fakeBridge.properties["chapter-list/0/title"] = "Intro"
        fakeBridge.properties["chapter-list/0/time"] = 0.0
        fakeBridge.emitProperty("chapter-list")
        assertEquals(1, engine.trackManager.chapters.value.size)

        fakeBridge.emitEndFile()

        assertTrue(engine.trackManager.chapters.value.isEmpty())
        assertEquals(-1, engine.trackManager.currentChapter.value)
    }

    // =========================================================================
    // R4: Volume Overdrive & Anti-Clipping Limiter Tests
    // =========================================================================

    @Test
    fun testVolumeOverdrive_setsGainAndActivatesLimiter() {
        engine.audio.setSoftwareVolume(150.0)

        assertEquals(150.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals(150.0, fakeBridge.properties["volume"] as Double, 0.001)
        assertEquals(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER, fakeBridge.properties["af"])
    }

    @Test
    fun testVolumeOverdrive_clamping() {
        engine.audio.setSoftwareVolume(250.0)
        assertEquals(200.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals(200.0, fakeBridge.properties["volume"] as Double, 0.001)

        engine.audio.setSoftwareVolume(50.0)
        assertEquals(100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals(100.0, fakeBridge.properties["volume"] as Double, 0.001)
    }

    @Test
    fun testVolumeOverdrive_limiterRemovedAt100() {
        engine.audio.setSoftwareVolume(175.0)
        assertEquals(dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER, fakeBridge.properties["af"])

        engine.audio.setSoftwareVolume(100.0)
        assertEquals("", fakeBridge.properties["af"])
    }

    @Test
    fun testVolumeOverdrive_resetSoftwareGainSafety() {
        engine.audio.setSoftwareVolume(180.0)
        assertEquals(180.0, engine.audio.softwareVolume.value, 0.001)

        engine.audio.resetSoftwareGain()

        assertEquals(100.0, engine.audio.softwareVolume.value, 0.001)
        assertEquals(100.0, fakeBridge.properties["volume"] as Double, 0.001)
        assertEquals("", fakeBridge.properties["af"])
    }

    // =========================================================================
    // R4: Visual AV Filter Tests (Deband & Colors)
    // =========================================================================

    @Test
    fun testDebandToggle() {
        assertFalse(engine.video.deband.value)

        engine.video.setDeband(true)
        assertTrue(engine.video.deband.value)
        assertEquals(true, fakeBridge.properties["deband"])

        engine.video.setDeband(false)
        assertFalse(engine.video.deband.value)
        assertEquals(false, fakeBridge.properties["deband"])
    }

    @Test
    fun testColorAdjustments_clampingAndReset() {
        engine.video.setBrightness(25)
        assertEquals(25, engine.video.brightness.value)
        assertEquals(25L, fakeBridge.properties["brightness"])

        engine.video.setContrast(120) // Clamps to 100
        assertEquals(100, engine.video.contrast.value)
        assertEquals(100L, fakeBridge.properties["contrast"])

        engine.video.setSaturation(-150) // Clamps to -100
        assertEquals(-100, engine.video.saturation.value)
        assertEquals(-100L, fakeBridge.properties["saturation"])

        engine.video.resetColors()
        assertEquals(0, engine.video.brightness.value)
        assertEquals(0, engine.video.contrast.value)
        assertEquals(0, engine.video.saturation.value)
        assertEquals(0L, fakeBridge.properties["brightness"])
        assertEquals(0L, fakeBridge.properties["contrast"])
        assertEquals(0L, fakeBridge.properties["saturation"])
    }

    // =========================================================================
    // R4: Composite Audio Filter Coordinator Tests
    // =========================================================================

    @Test
    fun testCompositeAudioFilters_orderingAndCombinations() {
        // 1. Initial state: no filters
        assertEquals(null, fakeBridge.properties["af"])

        // 2. Enable night mode
        engine.audio.setNightMode(true)
        assertEquals(dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM, fakeBridge.properties["af"])

        // 3. Enable stereo downmixing -> pan MUST precede dynaudnorm
        engine.audio.setStereoDownmix(true)
        val expectedDownmixAndNight =
            "${dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX},${dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM}"
        assertEquals(expectedDownmixAndNight, fakeBridge.properties["af"])

        // 4. Boost volume into overdrive -> alimiter MUST follow dynaudnorm
        engine.audio.setSoftwareVolume(160.0)
        val expectedAllThree =
            "${dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX},${dev.gyro.engine.audio.AudioEffectsController.FILTER_DYNAUDNORM},${dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expectedAllThree, fakeBridge.properties["af"])

        // 5. Disable night mode -> pan and alimiter remain
        engine.audio.setNightMode(false)
        val expectedDownmixAndLimiter =
            "${dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX},${dev.gyro.engine.audio.AudioEffectsController.FILTER_ALIMITER}"
        assertEquals(expectedDownmixAndLimiter, fakeBridge.properties["af"])

        // 6. Reset software gain -> only pan remains
        engine.audio.resetSoftwareGain()
        assertEquals(dev.gyro.engine.audio.AudioEffectsController.FILTER_STEREO_DOWNMIX, fakeBridge.properties["af"])

        // 7. Disable stereo downmixing -> chain becomes empty
        engine.audio.setStereoDownmix(false)
        assertEquals("", fakeBridge.properties["af"])
    }
}
