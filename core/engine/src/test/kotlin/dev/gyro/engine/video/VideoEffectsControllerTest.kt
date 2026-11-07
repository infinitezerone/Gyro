package dev.gyro.engine.video

import dev.gyro.core.testing.fakes.FakeMpvNativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoEffectsControllerTest {

    private lateinit var bridge: FakeMpvNativeBridge
    private lateinit var controller: VideoEffectsController

    @Before
    fun setUp() {
        bridge = FakeMpvNativeBridge()
        controller = VideoEffectsController(bridge)
    }

    @Test
    fun testInitialState() {
        assertFalse(controller.deband.value)
        assertEquals(0, controller.brightness.value)
        assertEquals(0, controller.contrast.value)
        assertEquals(0, controller.saturation.value)
    }

    @Test
    fun testDebandToggle() {
        controller.setDeband(true)
        assertTrue(controller.deband.value)
        assertEquals(true, bridge.properties["deband"])

        controller.setDeband(false)
        assertFalse(controller.deband.value)
        assertEquals(false, bridge.properties["deband"])
    }

    @Test
    fun testColorAdjustments_clampingAndReset() {
        controller.setBrightness(25)
        assertEquals(25, controller.brightness.value)
        assertEquals(25L, bridge.properties["brightness"])

        controller.setContrast(120) // Clamps to 100
        assertEquals(100, controller.contrast.value)
        assertEquals(100L, bridge.properties["contrast"])

        controller.setSaturation(-150) // Clamps to -100
        assertEquals(-100, controller.saturation.value)
        assertEquals(-100L, bridge.properties["saturation"])

        controller.resetColors()
        assertEquals(0, controller.brightness.value)
        assertEquals(0, controller.contrast.value)
        assertEquals(0, controller.saturation.value)
        assertEquals(0L, bridge.properties["brightness"])
        assertEquals(0L, bridge.properties["contrast"])
        assertEquals(0L, bridge.properties["saturation"])
    }

    @Test
    fun testPropertyObserverUpdates() {
        controller.onColorPropertyChanged("brightness", 15)
        assertEquals(15, controller.brightness.value)

        controller.onColorPropertyChanged("contrast", -30)
        assertEquals(-30, controller.contrast.value)

        controller.onColorPropertyChanged("saturation", 45)
        assertEquals(45, controller.saturation.value)

        controller.onDebandPropertyChanged(true)
        assertTrue(controller.deband.value)
    }
}
