package com.harryl.island

import kotlin.math.max
import kotlin.math.min

/** Camera cutout bounds in screen pixels. Plain ints so the layout maths is unit-testable on the JVM. */
data class CameraBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class Side { LEFT, CENTER, RIGHT }

/**
 * Where the island lives for one screen + camera combination.
 *
 * With a camera, the pill is built *around* it: its height is the camera height plus an even ring,
 * and its rounded end is concentric with the camera, so the hole sits dead-centre in the end cap.
 * Without a top camera (landscape, no cutout) it floats at the top centre of the status bar.
 */
data class IslandLayout(
    val screenW: Int,
    val screenH: Int,
    val holeL: Float,
    val holeT: Float,
    val holeR: Float,
    val holeB: Float,
    val side: Side,
    val pillH: Float,
    val bandTop: Float,
    /** Horizontal padding between the hole and the pill edge. */
    val padH: Float,
    val cardL: Float,
    val cardR: Float,
    val followsCamera: Boolean,
    /**
     * Horizontal span that content in the camera band must stay clear of. Equals the hole, or the
     * whole reported cutout box when the hole was only estimated inside it: the real camera is
     * somewhere in that box, so text that starts after it can never land on the lens.
     */
    val clearL: Float,
    val clearR: Float,
) {
    val holeCenterX: Float get() = (holeL + holeR) / 2
    val holeCenterY: Float get() = (holeT + holeB) / 2

    companion object {
        /** Black ring kept around the camera. */
        const val RING_DP = 6f
        const val MIN_PILL_DP = 30f
        const val FLOAT_PILL_DP = 30f
        const val MARGIN_DP = 10f
        const val MAX_CARD_DP = 420f

        /**
         * Realme and OPPO report a hole-punch camera as a padded box that runs into the screen
         * corner (on the X7 Max about 150 x 108 px from the top-left corner). A real hole never
         * touches the screen edge, so such a box is padding. Estimate the hole inside it: half the
         * box height across, its centre two thirds of the way down the box (the box runs from the
         * screen top, so it has more room above the camera than below) and half the box height in
         * from the far side. Measured against a real X7 Max. Anything that is not a corner box is
         * returned unchanged.
         */
        fun refineCornerCutout(box: CameraBox, screenW: Int): CameraBox {
            val touchesTop = box.top <= 2
            val nearLeft = box.left <= screenW * 0.05f
            val nearRight = box.right >= screenW * 0.95f
            if (!touchesTop || nearLeft == nearRight) return box
            val h = box.height
            val d = max(1, h / 2)
            val cy = box.top + h * 2 / 3
            val rawCx = if (nearLeft) box.right - h / 2 else box.left + h / 2
            val cx = rawCx.coerceIn(box.left + d / 2, max(box.left + d / 2, box.right - d / 2))
            val l = cx - d / 2
            val t = cy - d / 2
            return CameraBox(l, t, l + d, t + d)
        }

        /** Rejects cutouts that cannot be a top camera (too wide, too tall, too far down). */
        fun usableCamera(camera: CameraBox?, screenW: Int, screenH: Int): CameraBox? = camera?.takeIf {
            it.width > 0 && it.height > 0 &&
                it.width < screenW * 0.6f &&
                it.height < screenH / 8 &&
                it.top < screenH / 4
        }

        fun compute(
            screenW: Int,
            screenH: Int,
            density: Float,
            camera: CameraBox?,
            statusBarH: Int,
            offsetXdp: Int,
            offsetYdp: Int,
            forceCenter: Boolean,
            /** The padded cutout box the hole was estimated from, if it was estimated. */
            keepOut: CameraBox? = null,
        ): IslandLayout {
            fun dp(v: Float) = v * density
            val offX = dp(offsetXdp.toFloat())
            val offY = dp(offsetYdp.toFloat())
            val cam = if (forceCenter) null else usableCamera(camera, screenW, screenH)

            val hl: Float
            val ht: Float
            val hr: Float
            val hb: Float
            val pillH: Float
            val padH: Float
            if (cam != null) {
                hl = cam.left + offX
                hr = cam.right + offX
                ht = cam.top + offY
                hb = cam.bottom + offY
                val w = cam.width.toFloat()
                val h = cam.height.toFloat()
                pillH = max(dp(MIN_PILL_DP), h + 2 * dp(RING_DP))
                val roundish = w <= h * 1.35f
                // Concentric end cap for a hole punch; a fixed side gap for a wide notch.
                padH = if (roundish) (pillH - w) / 2 else dp(RING_DP + 4)
            } else {
                pillH = dp(FLOAT_PILL_DP)
                val top = max(dp(4f), (statusBarH - pillH) / 2)
                val cx = screenW / 2f + offX
                val cy = top + pillH / 2 + offY
                hl = cx; hr = cx; ht = cy; hb = cy
                padH = pillH / 2
            }

            val cx = (hl + hr) / 2
            val cy = (ht + hb) / 2
            val bandTop = cy - pillH / 2
            val side = when {
                cam == null -> Side.CENTER
                cx < screenW * 0.35f -> Side.LEFT
                cx > screenW * 0.65f -> Side.RIGHT
                else -> Side.CENTER
            }

            val margin = dp(MARGIN_DP)
            val maxCard = min(dp(MAX_CARD_DP), screenW - 2 * margin)
            val cardL: Float
            val cardR: Float
            when (side) {
                // The card grows away from the camera, so it is limited by the far margin only.
                Side.LEFT -> {
                    cardL = hl - padH
                    cardR = min(screenW - margin, cardL + dp(MAX_CARD_DP))
                }
                Side.RIGHT -> {
                    cardR = hr + padH
                    cardL = max(margin, cardR - dp(MAX_CARD_DP))
                }
                Side.CENTER -> {
                    val l = (cx - maxCard / 2).coerceIn(margin, max(margin, screenW - margin - maxCard))
                    cardL = l
                    cardR = l + maxCard
                }
            }
            return IslandLayout(
                screenW, screenH, hl, ht, hr, hb, side, pillH, bandTop, padH, cardL, cardR, cam != null,
                clearL = if (cam != null && keepOut != null) min(hl, keepOut.left.toFloat()) else hl,
                clearR = if (cam != null && keepOut != null) max(hr, keepOut.right.toFloat()) else hr,
            )
        }
    }
}

/** Formatting helpers for timers and stopwatches. */
object Clock {
    fun format(ms: Long): String {
        val total = max(0L, ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private val token = Regex("""\b\d{1,2}:\d{2}(:\d{2})?(\.\d{1,2})?\b""")

    /** First clock-looking token in a notification's text, such as "01:23" or "1:02:03". */
    fun timeToken(text: String): String? = token.find(text)?.value

    /** Same width as [text] for tabular digits, used to keep the pill from wobbling every second. */
    fun widthTemplate(text: String): String = text.replace(Regex("\\d"), "0")
}
