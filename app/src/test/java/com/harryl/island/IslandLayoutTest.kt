package com.harryl.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLayoutTest {

    private val density = 2.75f
    private fun dp(v: Float) = v * density
    private val eps = 0.01f

    // Realme X7 Max: 1080x2400, hole punch near the top-left corner.
    private val x7Camera = CameraBox(36, 27, 96, 87)

    private fun layout(
        camera: CameraBox?,
        w: Int = 1080,
        h: Int = 2400,
        offX: Int = 0,
        offY: Int = 0,
        forceCenter: Boolean = false,
    ) = IslandLayout.compute(w, h, density, camera, statusBarH = 90, offsetXdp = offX, offsetYdp = offY, forceCenter = forceCenter)

    /** Centre of the rounded end cap on the camera side of the pill. */
    private fun capCenterX(l: IslandLayout): Float = when (l.side) {
        Side.RIGHT -> l.holeR + l.padH - l.pillH / 2
        else -> l.holeL - l.padH + l.pillH / 2
    }

    @Test
    fun holePunchOnLeftIsWrappedConcentrically() {
        val l = layout(x7Camera)
        assertTrue(l.followsCamera)
        assertEquals(Side.LEFT, l.side)
        assertEquals(60f + 2 * dp(IslandLayout.RING_DP), l.pillH, eps)
        // Camera centre == centre of the pill's left end cap, both axes.
        assertEquals(66f, capCenterX(l), eps)
        assertEquals(57f, l.bandTop + l.pillH / 2, eps)
        // Expanded card starts where the pill starts and stays on screen.
        assertEquals(l.holeL - l.padH, l.cardL, eps)
        assertTrue(l.cardL >= 0f)
        assertEquals(1080 - dp(IslandLayout.MARGIN_DP), l.cardR, eps)
    }

    @Test
    fun holePunchOnRightIsMirrored() {
        val l = layout(CameraBox(1080 - 96, 27, 1080 - 36, 87))
        assertEquals(Side.RIGHT, l.side)
        assertEquals(1080f - 66f, capCenterX(l), eps)
        assertEquals(l.holeR + l.padH, l.cardR, eps)
        assertTrue(l.cardL >= dp(IslandLayout.MARGIN_DP) - eps)
    }

    @Test
    fun centreHolePunchKeepsCardAroundIt() {
        val l = layout(CameraBox(510, 27, 570, 87))
        assertEquals(Side.CENTER, l.side)
        assertTrue(l.cardL < 510 && l.cardR > 570)
        assertEquals(540f, (l.cardL + l.cardR) / 2, eps)
    }

    @Test
    fun tinyCameraStillGetsMinimumPill() {
        val l = layout(CameraBox(40, 30, 60, 50))
        assertEquals(dp(IslandLayout.MIN_PILL_DP), l.pillH, eps)
        assertEquals(50f, capCenterX(l), eps)
    }

    @Test
    fun noTopCameraFloatsAtTopCentre() {
        val l = layout(camera = null, w = 2400, h = 1080)
        assertFalse(l.followsCamera)
        assertEquals(Side.CENTER, l.side)
        assertEquals(1200f, l.holeCenterX, eps)
        val cardW = dp(IslandLayout.MAX_CARD_DP)
        assertEquals(cardW, l.cardR - l.cardL, eps)
        assertEquals(1200f, (l.cardL + l.cardR) / 2, eps)
    }

    @Test
    fun forceCentreIgnoresCamera() {
        val l = layout(x7Camera, forceCenter = true)
        assertFalse(l.followsCamera)
        assertEquals(540f, l.holeCenterX, eps)
    }

    @Test
    fun nudgesShiftTheHole() {
        val l = layout(x7Camera, offX = 2, offY = -1)
        assertEquals(66f + dp(2f), capCenterX(l), eps)
        assertEquals(57f - dp(1f), l.bandTop + l.pillH / 2, eps)
    }

    @Test
    fun implausibleCutoutsAreRejected() {
        assertNull(IslandLayout.usableCamera(CameraBox(0, 0, 900, 80), 1080, 2400))
        assertNull(IslandLayout.usableCamera(CameraBox(0, 1200, 60, 1260), 1080, 2400))
        assertNull(IslandLayout.usableCamera(CameraBox(10, 10, 10, 50), 1080, 2400))
    }

    @Test
    fun wideNotchUsesSideGap() {
        val l = layout(CameraBox(340, 0, 740, 80))
        assertEquals(Side.CENTER, l.side)
        assertEquals(dp(IslandLayout.RING_DP + 4), l.padH, eps)
    }

    @Test
    fun realmeCornerBoxIsRefinedToTheHoleInside() {
        // What the X7 Max reports: a padded box from the top-left corner.
        val box = CameraBox(0, 0, 150, 108)
        val hole = IslandLayout.refineCornerCutout(box, 1080)
        assertEquals(54, hole.width)
        assertEquals(54, hole.height)
        // Matches the hand-tuned position on a real X7 Max: no left/right nudge, +6 dp (18 px) down.
        assertEquals(96, (hole.left + hole.right) / 2)
        assertEquals(72, (hole.top + hole.bottom) / 2)
        // The pill around it stays on screen and inside the status bar area.
        val l = layout(hole)
        assertTrue(l.cardL > 0f)
        assertTrue(l.bandTop >= 0f)
        assertEquals(54 + 2 * dp(IslandLayout.RING_DP), l.pillH, eps)
        // Far smaller than wrapping the whole reported box, which made a 144 px tall pill.
        assertTrue(l.pillH < 108)
    }

    @Test
    fun textStaysClearOfTheWholeReportedBox() {
        val box = CameraBox(0, 0, 150, 108)
        val hole = IslandLayout.refineCornerCutout(box, 1080)
        val l = IslandLayout.compute(1080, 2400, density, hole, 90, 0, 0, false, keepOut = box)
        assertEquals(hole.right.toFloat(), l.holeR, eps)
        assertEquals(150f, l.clearR, eps)
        // Without a keep-out box, the clear span is just the hole.
        val exact = layout(x7Camera)
        assertEquals(exact.holeR, exact.clearR, eps)
        assertEquals(exact.holeL, exact.clearL, eps)
    }

    @Test
    fun rightCornerBoxIsMirrored() {
        val hole = IslandLayout.refineCornerCutout(CameraBox(930, 0, 1080, 108), 1080)
        assertEquals(1080 - 96, (hole.left + hole.right) / 2)
    }

    @Test
    fun realHolesAndNotchesAreLeftAlone() {
        assertEquals(x7Camera, IslandLayout.refineCornerCutout(x7Camera, 1080))
        val waterdrop = CameraBox(480, 0, 600, 80)
        assertEquals(waterdrop, IslandLayout.refineCornerCutout(waterdrop, 1080))
        val fullWidth = CameraBox(0, 0, 1080, 100)
        assertEquals(fullWidth, IslandLayout.refineCornerCutout(fullWidth, 1080))
    }

    @Test
    fun clockFormatting() {
        assertEquals("00:05", Clock.format(5_400))
        assertEquals("01:23", Clock.format(83_000))
        assertEquals("1:02:03", Clock.format(3_723_000))
        assertEquals("00:00", Clock.format(-50))
        assertEquals("12:34", Clock.timeToken("Stopwatch 12:34 running"))
        assertEquals("00:00", Clock.widthTemplate("12:34"))
        assertNull(Clock.timeToken("Downloading file"))
    }
}
