package dev.gyro.engine.video

import dev.gyro.core.model.HdrOutputMode
import dev.gyro.engine.model.HdrDisplayCapabilities
import dev.gyro.engine.model.HdrDisplayCapabilities.HdrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output decision table. Every case that used to be buried in `updateHdrState` and could only
 * be checked on a real panel is pinned here instead.
 */
class HdrOutputPlannerTest {

    private val hdr10Panel = HdrDisplayCapabilities(setOf(HdrType.HDR10), peakLuminanceNits = 900f)
    private val hlgPanel = HdrDisplayCapabilities(
        supportedHdrTypes = setOf(HdrType.HDR10, HdrType.HLG),
        peakLuminanceNits = 900f,
    )
    private val sdrPanel = HdrDisplayCapabilities.SdrOnly
    private val bothColorSpaces = HdrColorSpaceSupport(pq = true, hlg = true)

    @Test
    fun sdrSourceNeverClaimsHdr() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.NONE,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.AUTO,
        )

        assertEquals(OutputTransfer.AUTO, target.transfer)
        assertFalse(target.isHdr)
        assertEquals("sdr-source", target.reason)
    }

    @Test
    fun pqSourceOnHdrPanelPassesThroughWithThePanelPeak() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HDR10,
            display = hdr10Panel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.AUTO,
        )

        assertEquals(OutputTransfer.PQ, target.transfer)
        assertEquals("bt.2020", target.primaries)
        assertEquals(900f, target.peakNits)
        assertFalse(target.gamutClipping)
    }

    @Test
    fun hlgSourcePassesThroughUntouchedWhenBothSidesSupportHlg() {
        // Converting HLG to PQ costs a second tone-map at the panel and needs metadata we cannot
        // supply; the gallery plays the same file as HLG and looks right, so AUTO follows it.
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HLG,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.AUTO,
        )

        assertEquals(OutputTransfer.HLG, target.transfer)
        assertEquals("hlg-pass-through", target.reason)
    }

    @Test
    fun explicitPqPreferenceStillConvertsHlg() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HLG,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.PQ,
        )

        assertEquals(OutputTransfer.PQ, target.transfer)
        assertEquals("hlg-forced-to-pq", target.reason)
    }

    @Test
    fun hlgPreferenceUsesTheHlgSurfaceOnlyWhenBothSidesSupportIt() {
        val supported = HdrOutputPlanner.plan(
            format = HdrFormat.HLG,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.HLG,
        )
        assertEquals(OutputTransfer.HLG, supported.transfer)
        assertEquals("hlg-pass-through", supported.reason)

        // Same preference on a panel that only knows HDR10 must not invent an HLG surface.
        val panelWithoutHlg = HdrOutputPlanner.plan(
            format = HdrFormat.HLG,
            display = hdr10Panel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.HLG,
        )
        assertEquals(OutputTransfer.PQ, panelWithoutHlg.transfer)
        assertEquals("hlg-to-pq", panelWithoutHlg.reason)
    }

    @Test
    fun hdrSourceOnAnSdrPanelFoldsIntoBt709() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HLG,
            display = sdrPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.AUTO,
        )

        assertFalse(target.isHdr)
        assertEquals("bt.709", target.primaries)
        assertTrue(target.foldsToSdr)
        assertEquals("panel-sdr", target.reason)
    }

    @Test
    fun sdrFoldTargetsMpvOwnSdrReferenceInsteadOfThePanelHdrPeak() {
        // Forcing SDR on a 900-nit panel: the old code handed `target-peak=900` to a tone mapper
        // that was encoding an SDR curve, which reads as blown out.
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HDR10,
            display = hdr10Panel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.SDR,
        )

        assertEquals(OutputTransfer.AUTO, target.transfer)
        assertEquals("bt.709", target.primaries)
        assertTrue(target.foldsToSdr)
        assertEquals(0f, target.peakNits)
        assertFalse(target.gamutClipping)
        assertEquals("user-forced-sdr", target.reason)
    }

    @Test
    fun missingPqColorSpaceFoldsInsteadOfOutputtingPqIntoAnSdrBuffer() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HDR10,
            display = hdr10Panel,
            colorSpace = HdrColorSpaceSupport(pq = false, hlg = false),
            preference = HdrOutputMode.AUTO,
        )

        assertFalse(target.isHdr)
        assertTrue(target.foldsToSdr)
        assertEquals("no-pq-colorspace", target.reason)
    }

    @Test
    fun unprobedColorSpaceKeepsTheOptimisticPath() {
        // A render thread that has not probed yet must not be read as "unsupported".
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HDR10,
            display = hdr10Panel,
            colorSpace = HdrColorSpaceSupport.Unknown,
            preference = HdrOutputMode.AUTO,
        )

        assertEquals(OutputTransfer.PQ, target.transfer)
    }

    @Test
    fun dolbyVisionIsNeverPassedThrough() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.DOLBY_VISION,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.PQ,
        )

        assertFalse(target.isHdr)
        assertEquals("dolby-vision-unsupported", target.reason)
    }

    @Test
    fun userForcedSdrAlwaysWins() {
        val target = HdrOutputPlanner.plan(
            format = HdrFormat.HDR10,
            display = hlgPanel,
            colorSpace = bothColorSpaces,
            preference = HdrOutputMode.SDR,
        )

        assertFalse(target.isHdr)
        assertEquals("user-forced-sdr", target.reason)
    }
}
