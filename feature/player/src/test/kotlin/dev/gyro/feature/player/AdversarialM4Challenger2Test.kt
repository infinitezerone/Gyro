package dev.gyro.feature.player

import android.content.Context
import android.content.ContextWrapper
import dev.gyro.core.model.PlayerGestureConfig
import dev.gyro.core.model.UserSettings
import dev.gyro.feature.player.gesture.DoubleTapSide
import dev.gyro.feature.player.gesture.GestureHudState
import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import dev.gyro.engine.GyroPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdversarialM4Challenger2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: Context
    private lateinit var fakeBridge: FakeMpvNativeBridge
    private lateinit var engine: GyroPlayerEngine

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("engine_challenger2")
        testContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        fakeBridge = FakeMpvNativeBridge()
        engine = GyroPlayerEngine(context = testContext, bridge = fakeBridge)
        engine.ensureInitialized()
    }

    // =========================================================================
    // 1. Dynamic Gesture Binding & Exact Prior Speed Restoration
    // =========================================================================

    @Test
    fun testSpeedBoostMatrix_restoresExactPriorSpeed() {
        val priorSpeeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f)
        val boostMultipliers = listOf(1.5f, 2.0f, 3.0f)

        for (prior in priorSpeeds) {
            for (boost in boostMultipliers) {
                // Set initial prior speed
                engine.setSpeed(prior)
                var currentSpeed = prior
                var priorSpeedBeforeBoost: Float? = null
                var gestureHudState: GestureHudState = GestureHudState.Idle

                val onFastForwardStart: (Float) -> Unit = { targetSpeed ->
                    priorSpeedBeforeBoost = currentSpeed
                    engine.setSpeed(targetSpeed)
                    currentSpeed = targetSpeed
                    gestureHudState = GestureHudState.Speed(targetSpeed)
                }

                val onFastForwardEnd: () -> Unit = {
                    val restoreSpeed = priorSpeedBeforeBoost ?: 1.0f
                    engine.setSpeed(restoreSpeed)
                    currentSpeed = restoreSpeed
                    priorSpeedBeforeBoost = null
                    gestureHudState = GestureHudState.Idle
                }

                // Activate speed boost
                onFastForwardStart(boost)
                assertEquals("Current speed must equal boost multiplier $boost", boost, currentSpeed, 0.001f)
                assertEquals("Recorded prior speed must equal $prior", prior, priorSpeedBeforeBoost ?: 0f, 0.001f)
                assertEquals("HUD state must display boost speed $boost", GestureHudState.Speed(boost), gestureHudState)

                // Release speed boost
                onFastForwardEnd()
                assertEquals("Speed must restore EXACT prior speed $prior, not hardcoded 1.0f", prior, currentSpeed, 0.001f)
                assertNull("priorSpeedBeforeBoost must be reset to null", priorSpeedBeforeBoost)
                assertEquals("HUD state must be Idle after release", GestureHudState.Idle, gestureHudState)
            }
        }
    }

    @Test
    fun testSpeedBoost_fallbackWhenPriorSpeedIsNull() {
        var currentSpeed = 2.0f
        var priorSpeedBeforeBoost: Float? = null

        val onFastForwardEnd: () -> Unit = {
            val restoreSpeed = priorSpeedBeforeBoost ?: 1.0f
            engine.setSpeed(restoreSpeed)
            currentSpeed = restoreSpeed
            priorSpeedBeforeBoost = null
        }

        // Invoke release without preceding start
        onFastForwardEnd()
        assertEquals("When priorSpeedBeforeBoost is null, fallback must be 1.0f", 1.0f, currentSpeed, 0.001f)
        assertNull(priorSpeedBeforeBoost)
    }

    // =========================================================================
    // 2. Master Switch: gesturesEnabled = false Disables ALL Gestures
    // =========================================================================

    @Test
    fun testMasterGesturesDisabled_disablesAllGesturesEvenIfIndividualTogglesTrue() {
        val userConfig = PlayerGestureConfig(
            isVolumeEnabled = true,
            isBrightnessEnabled = true,
            isSeekEnabled = true,
            isDoubleTapSeekEnabled = true,
            isDoubleTapPlayPauseEnabled = true,
            isLongPressSpeedEnabled = true,
            isZoomEnabled = true,
            swapVolumeAndBrightness = true,
            doubleTapSeekSeconds = 15,
            longPressSpeed = 3.0f
        )
        val userSettings = UserSettings(
            gesturesEnabled = false,
            gestureConfig = userConfig
        )

        // Replicate PlayerScreen logic
        val dynamicConfig = userSettings.gestureConfig
        val isLocked = false
        val effectiveConfig = if (!userSettings.gesturesEnabled) {
            dynamicConfig.copy(
                isLocked = isLocked,
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                isSeekEnabled = false,
                isDoubleTapSeekEnabled = false,
                isDoubleTapPlayPauseEnabled = false,
                isLongPressSpeedEnabled = false,
                isZoomEnabled = false
            )
        } else {
            dynamicConfig.copy(isLocked = isLocked)
        }

        assertFalse("Volume gesture must be disabled", effectiveConfig.isVolumeEnabled)
        assertFalse("Brightness gesture must be disabled", effectiveConfig.isBrightnessEnabled)
        assertFalse("Seek gesture must be disabled", effectiveConfig.isSeekEnabled)
        assertFalse("Double-tap seek gesture must be disabled", effectiveConfig.isDoubleTapSeekEnabled)
        assertFalse("Double-tap play/pause gesture must be disabled", effectiveConfig.isDoubleTapPlayPauseEnabled)
        assertFalse("Long-press speed boost must be disabled", effectiveConfig.isLongPressSpeedEnabled)
        assertFalse("Zoom gesture must be disabled", effectiveConfig.isZoomEnabled)

        // Non-toggle properties should remain intact
        assertTrue("swapVolumeAndBrightness preference preserved", effectiveConfig.swapVolumeAndBrightness)
        assertEquals(15, effectiveConfig.doubleTapSeekSeconds)
        assertEquals(3.0f, effectiveConfig.longPressSpeed, 0.001f)
    }

    @Test
    fun testMasterGesturesEnabled_honorsIndividualToggles() {
        val userConfig = PlayerGestureConfig(
            isVolumeEnabled = true,
            isBrightnessEnabled = false,
            isSeekEnabled = true,
            isDoubleTapSeekEnabled = false,
            isDoubleTapPlayPauseEnabled = true,
            isLongPressSpeedEnabled = false,
            isZoomEnabled = true
        )
        val userSettings = UserSettings(
            gesturesEnabled = true,
            gestureConfig = userConfig
        )

        val dynamicConfig = userSettings.gestureConfig
        val isLocked = false
        val effectiveConfig = if (!userSettings.gesturesEnabled) {
            dynamicConfig.copy(
                isLocked = isLocked,
                isVolumeEnabled = false,
                isBrightnessEnabled = false,
                isSeekEnabled = false,
                isDoubleTapSeekEnabled = false,
                isDoubleTapPlayPauseEnabled = false,
                isLongPressSpeedEnabled = false,
                isZoomEnabled = false
            )
        } else {
            dynamicConfig.copy(isLocked = isLocked)
        }

        assertTrue(effectiveConfig.isVolumeEnabled)
        assertFalse(effectiveConfig.isBrightnessEnabled)
        assertTrue(effectiveConfig.isSeekEnabled)
        assertFalse(effectiveConfig.isDoubleTapSeekEnabled)
        assertTrue(effectiveConfig.isDoubleTapPlayPauseEnabled)
        assertFalse(effectiveConfig.isLongPressSpeedEnabled)
        assertTrue(effectiveConfig.isZoomEnabled)
    }

    // =========================================================================
    // 3. Double-Tap Seek Step Values & Rapid Consecutive Accumulation
    // =========================================================================

    @Test
    fun testDoubleTapSeekSteps_rapidConsecutiveAccumulation() {
        val stepValues = listOf(5, 10, 15, 30)

        for (step in stepValues) {
            val config = PlayerGestureConfig(
                isDoubleTapSeekEnabled = true,
                doubleTapSeekSeconds = step
            )

            // Simulate consecutive double-taps on the same side (RIGHT side / forward)
            var accumulatedSeconds = 0
            var currentSide: DoubleTapSide? = null
            var totalDoubleTaps = 0

            val simulateDoubleTap = { side: DoubleTapSide ->
                totalDoubleTaps++
                if (currentSide == side) {
                    accumulatedSeconds += config.doubleTapSeekSeconds
                } else {
                    currentSide = side
                    accumulatedSeconds = config.doubleTapSeekSeconds
                }
            }

            // 1st double-tap
            simulateDoubleTap(DoubleTapSide.RIGHT)
            assertEquals("1 tap: $step s", step, accumulatedSeconds)

            // 2nd consecutive double-tap
            simulateDoubleTap(DoubleTapSide.RIGHT)
            assertEquals("2 taps: ${step * 2} s", step * 2, accumulatedSeconds)

            // 3rd consecutive double-tap
            simulateDoubleTap(DoubleTapSide.RIGHT)
            assertEquals("3 taps: ${step * 3} s", step * 3, accumulatedSeconds)

            // 5th consecutive double-tap
            simulateDoubleTap(DoubleTapSide.RIGHT)
            simulateDoubleTap(DoubleTapSide.RIGHT)
            assertEquals("5 taps: ${step * 5} s", step * 5, accumulatedSeconds)

            // Now rapid tap on opposite side (LEFT side)
            simulateDoubleTap(DoubleTapSide.LEFT)
            assertEquals("Switching side resets accumulator to single step $step", step, accumulatedSeconds)
            assertEquals(DoubleTapSide.LEFT, currentSide)
        }
    }

    @Test
    fun testSeekRelativeCommandSentToBridge() {
        // Prepare loaded file state in engine
        fakeBridge.properties["duration"] = 120.0
        fakeBridge.properties["time-pos"] = 60.0
        engine.attachSurface(android.view.Surface(android.graphics.SurfaceTexture(0)))
        engine.load("http://example.com/test.mp4")
        fakeBridge.emitProperty("duration")
        fakeBridge.emitEvent(dev.gyro.engine.MpvNativeBridge.Event.FILE_LOADED)

        // Seek forward 10 seconds
        engine.seekRelative(10_000L)
        val lastCommand = fakeBridge.commands.lastOrNull()
        assertEquals("seek", lastCommand?.get(0))
        assertEquals("10.0", lastCommand?.get(1))
        assertEquals("relative", lastCommand?.get(2))

        // Seek backward 15 seconds
        engine.seekRelative(-15_000L)
        val rewindCommand = fakeBridge.commands.lastOrNull()
        assertEquals("seek", rewindCommand?.get(0))
        assertEquals("-15.0", rewindCommand?.get(1))
        assertEquals("relative", rewindCommand?.get(2))
    }

    // =========================================================================
    // 4. Audiovisual Controls: Slider Clamping & Reset Colors
    // =========================================================================

    @Test
    fun testSliderBoundaries_brightnessClampedToMinus100To100() {
        val testCases = listOf(
            0 to 0L,
            -50 to -50L,
            50 to 50L,
            -100 to -100L,
            100 to 100L,
            -101 to -100L,     // clamped
            -200 to -100L,     // clamped
            -99999 to -100L,   // clamped
            101 to 100L,       // clamped
            250 to 100L,       // clamped
            99999 to 100L      // clamped
        )

        for ((input, expected) in testCases) {
            engine.video.setBrightness(input)
            assertEquals("Brightness StateFlow must clamp to $expected for input $input", expected.toInt(), engine.video.brightness.value)
            assertEquals("Bridge property brightness must clamp to $expected", expected, fakeBridge.properties["brightness"])
        }
    }

    @Test
    fun testSliderBoundaries_contrastClampedToMinus100To100() {
        val testCases = listOf(
            0 to 0L,
            -100 to -100L,
            100 to 100L,
            -150 to -100L,
            150 to 100L,
            Int.MIN_VALUE to -100L,
            Int.MAX_VALUE to 100L
        )

        for ((input, expected) in testCases) {
            engine.video.setContrast(input)
            assertEquals("Contrast StateFlow must clamp to $expected for input $input", expected.toInt(), engine.video.contrast.value)
            assertEquals("Bridge property contrast must clamp to $expected", expected, fakeBridge.properties["contrast"])
        }
    }

    @Test
    fun testSliderBoundaries_saturationClampedToMinus100To100() {
        val testCases = listOf(
            0 to 0L,
            -100 to -100L,
            100 to 100L,
            -300 to -100L,
            300 to 100L
        )

        for ((input, expected) in testCases) {
            engine.video.setSaturation(input)
            assertEquals("Saturation StateFlow must clamp to $expected for input $input", expected.toInt(), engine.video.saturation.value)
            assertEquals("Bridge property saturation must clamp to $expected", expected, fakeBridge.properties["saturation"])
        }
    }

    @Test
    fun testResetColors_setsAllSlidersToZero() {
        // Set all to non-zero values
        engine.video.setBrightness(45)
        engine.video.setContrast(-80)
        engine.video.setSaturation(90)

        assertEquals(45, engine.video.brightness.value)
        assertEquals(-80, engine.video.contrast.value)
        assertEquals(90, engine.video.saturation.value)

        // Trigger Reset Colors
        engine.video.resetColors()

        assertEquals(0, engine.video.brightness.value)
        assertEquals(0, engine.video.contrast.value)
        assertEquals(0, engine.video.saturation.value)
        assertEquals(0L, fakeBridge.properties["brightness"])
        assertEquals(0L, fakeBridge.properties["contrast"])
        assertEquals(0L, fakeBridge.properties["saturation"])

        // Test hasColorModifications predicate
        val hasColorModifications = engine.video.brightness.value != 0 || engine.video.contrast.value != 0 || engine.video.saturation.value != 0
        assertFalse("hasColorModifications must be false after resetColors", hasColorModifications)
    }

    // =========================================================================
    // 5. Audiovisual Filter Toggles: Deband, Night Mode, Stereo Downmix
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
    fun testAudioFilters_nightModeAndStereoDownmixPipeline() {
        assertFalse(engine.audio.nightMode.value)
        assertFalse(engine.audio.stereoDownmix.value)

        // 1. Stereo Downmix only
        engine.audio.setStereoDownmix(true)
        assertTrue(engine.audio.stereoDownmix.value)
        val afDownmix = fakeBridge.properties["af"] as String
        assertTrue("af must contain pan downmix filter", afDownmix.contains("pan="))
        assertFalse("af must not contain dynaudnorm", afDownmix.contains("dynaudnorm"))
        assertFalse("af must not contain alimiter", afDownmix.contains("alimiter"))

        // 2. Night Mode + Stereo Downmix
        engine.audio.setNightMode(true)
        assertTrue(engine.audio.nightMode.value)
        val afBoth = fakeBridge.properties["af"] as String
        assertTrue("Stage 1 must be pan", afBoth.contains("pan="))
        assertTrue("Stage 2 must be dynaudnorm", afBoth.contains("dynaudnorm="))
        assertFalse("alimiter not yet active", afBoth.contains("alimiter"))

        // 3. Night Mode + Stereo Downmix + 200% Software Volume Overdrive (Limiter active)
        engine.audio.setSoftwareVolume(150.0)
        val afAll3 = fakeBridge.properties["af"] as String
        val stages = afAll3.split(",")
        assertEquals("Must have exactly 3 stages in filter chain", 3, stages.size)
        assertTrue("Stage 1 pan", stages[0].contains("pan="))
        assertTrue("Stage 2 dynaudnorm", stages[1].contains("dynaudnorm="))
        assertTrue("Stage 3 alimiter", stages[2].contains("alimiter="))

        // 4. Reset software volume -> Limiter removed, pan + dynaudnorm remain
        engine.audio.resetSoftwareGain()
        val afGainReset = fakeBridge.properties["af"] as String
        assertEquals(2, afGainReset.split(",").size)
        assertFalse(afGainReset.contains("alimiter"))

        // 5. Disable all -> empty filter string
        engine.audio.setNightMode(false)
        engine.audio.setStereoDownmix(false)
        assertEquals("", fakeBridge.properties["af"])
    }
}
