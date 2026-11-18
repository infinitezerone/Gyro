package dev.gyro.feature.player

import dev.gyro.feature.player.component.sheet.PlayerSheetTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetFitTransformTest {

    private val delta = 1e-3f
    private val screen = 2726f
    private val sheet = 1226f

    @Test
    fun inactive_collapsesToIdentity() {
        val fit = sheetFitTransform(videoHeightPx = 2262f, screenHeightPx = screen, sheetHeightPx = sheet, active = false)
        assertEquals(1f, fit.scale, delta)
        assertEquals(0f, fit.shiftY, delta)
    }

    @Test
    fun unmeasuredSheet_collapsesToIdentity() {
        val fit = sheetFitTransform(videoHeightPx = 2262f, screenHeightPx = screen, sheetHeightPx = 0f, active = true)
        assertEquals(1f, fit.scale, delta)
        assertEquals(0f, fit.shiftY, delta)
    }

    @Test
    fun landscapeClip_isOnlyLifted() {
        // 16:9 on a 1272-wide container displays ~715px tall, which already fits above the sheet.
        val fit = sheetFitTransform(videoHeightPx = 715f, screenHeightPx = screen, sheetHeightPx = sheet, active = true)
        assertEquals(1f, fit.scale, delta)
        assertEquals(-sheet / 2f, fit.shiftY, delta)
    }

    @Test
    fun verticalClip_shrinksToTheGap() {
        // A 9:16 clip fills the container; above the sheet only 1500px remain.
        val fit = sheetFitTransform(videoHeightPx = 2262f, screenHeightPx = screen, sheetHeightPx = sheet, active = true)
        val available = screen - sheet
        assertEquals(available / 2262f, fit.scale, delta)
        assertEquals(-sheet / 2f, fit.shiftY, delta)
    }

    @Test
    fun fittedPictureEndsExactlyOnTheSheetEdge() {
        val available = screen - sheet
        listOf(715f, 1500f, 2262f, 2726f).forEach { videoHeight ->
            val fit = sheetFitTransform(videoHeight, screen, sheet, active = true)
            val centre = screen / 2f + fit.shiftY
            val bottom = centre + videoHeight * fit.scale / 2f
            assertTrue(
                "bottom $bottom exceeds available $available for video $videoHeight",
                bottom <= available + 1f
            )
        }
    }

    @Test
    fun degenerateSheetHeight_doesNotCollapseThePicture() {
        val fit = sheetFitTransform(videoHeightPx = 2262f, screenHeightPx = screen, sheetHeightPx = screen, active = true)
        assertTrue(fit.scale >= 0.2f)
    }

    @Test
    fun isSheetFitActive_excludesPlaylistAndChaptersAndNonPortraitPipStates() {
        assertFalse(isSheetFitActive(null, isLandscape = false, isPipLayout = false))
        assertFalse(isSheetFitActive(PlayerSheetTab.PLAYLIST, isLandscape = false, isPipLayout = false))
        assertFalse(isSheetFitActive(PlayerSheetTab.CHAPTERS, isLandscape = false, isPipLayout = false))
        assertFalse(isSheetFitActive(PlayerSheetTab.SETTINGS_HUB, isLandscape = true, isPipLayout = false))
        assertFalse(isSheetFitActive(PlayerSheetTab.SETTINGS_HUB, isLandscape = false, isPipLayout = true))

        assertTrue(isSheetFitActive(PlayerSheetTab.SETTINGS_HUB, isLandscape = false, isPipLayout = false))
        assertTrue(isSheetFitActive(PlayerSheetTab.ASPECT, isLandscape = false, isPipLayout = false))
        assertTrue(isSheetFitActive(PlayerSheetTab.STATS, isLandscape = false, isPipLayout = false))
    }
}
