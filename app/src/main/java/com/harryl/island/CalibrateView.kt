package com.harryl.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Full-screen canvas for lining a ring up with the physical camera hole. The background is bright
 * so the black hole is easy to see. Drag anywhere to move the ring (the finger never has to cover
 * the camera), pinch to resize. Everything is in screen pixels.
 */
class CalibrateView(context: Context) : View(context) {

    var cx = 0f
    var cy = 0f
    var diameter = 0f
    var onChange: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val minD = 6 * density
    private val maxD = 60 * density
    private val loc = IntArray(2)
    private val rect = RectF()

    private val bg = 0xFFF2F2F7.toInt()
    private val accent = ContextCompat.getColor(context, R.color.island_accent)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = accent
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent and 0x33FFFFFF }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = accent
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0xFF8E8E93.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }

    private var lastX = 0f
    private var lastY = 0f
    private var pointer = -1
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            diameter = (diameter * detector.scaleFactor).coerceIn(minD, maxD)
            changed()
            return true
        }
    })

    fun nudge(dx: Float, dy: Float) {
        cx += dx
        cy += dy
        changed()
    }

    fun setSize(d: Float) {
        diameter = d.coerceIn(minD, maxD)
        changed()
    }

    val minDiameter get() = minD
    val maxDiameter get() = maxD

    private fun changed() {
        cx = cx.coerceIn(0f, max(1f, rootView.width.toFloat()))
        cy = cy.coerceIn(0f, max(1f, rootView.height.toFloat() / 3))
        invalidate()
        onChange?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        // Where the pill will sit: camera + 6 dp ring, end cap centred on the camera.
        val pillH = max(IslandLayout.MIN_PILL_DP * density, diameter + 2 * IslandLayout.RING_DP * density)
        val onRight = cx > (rootView.width / 2f)
        val len = pillH * 3.2f
        if (onRight) rect.set(cx + pillH / 2 - len, cy - pillH / 2, cx + pillH / 2, cy + pillH / 2)
        else rect.set(cx - pillH / 2, cy - pillH / 2, cx - pillH / 2 + len, cy + pillH / 2)
        canvas.drawRoundRect(rect, pillH / 2, pillH / 2, pill)

        val r = diameter / 2
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        val arm = r + 14 * density
        canvas.drawLine(cx - arm, cy, cx - r - 3 * density, cy, cross)
        canvas.drawLine(cx + r + 3 * density, cy, cx + arm, cy, cross)
        canvas.drawLine(cx, cy - arm, cx, cy - r - 3 * density, cross)
        canvas.drawLine(cx, cy + r + 3 * density, cx, cy + arm, cross)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointer = -1
            MotionEvent.ACTION_MOVE -> {
                if (pointer != -1 && event.pointerCount == 1 && !scaler.isInProgress) {
                    val i = event.findPointerIndex(pointer)
                    if (i >= 0) {
                        // Half speed for precision: the ring follows the finger from a distance.
                        nudge((event.getX(i) - lastX) * 0.5f, (event.getY(i) - lastY) * 0.5f)
                        lastX = event.getX(i)
                        lastY = event.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = -1
        }
        return true
    }
}
