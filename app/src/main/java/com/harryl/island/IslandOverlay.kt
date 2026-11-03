package com.harryl.island

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/** Owns the overlay window and keeps it exactly as large as the island needs to be. */
class IslandOverlay(
    context: Context,
    prefs: Prefs,
    listener: IslandView.Listener,
) : IslandView.Host {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = IslandView(context, prefs, this, listener)
    private var attached = false

    private val lp = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "RealmeIsland"
        width = 1
        height = 1
        x = 0
        y = 0
    }

    fun present(primary: IslandContent, companion: IslandContent?, expanded: Boolean) =
        view.present(primary, companion, expanded)

    fun hide() = view.dismiss()

    fun relayout() = view.relayout()

    fun destroy() {
        view.cancelAll()
        detach()
    }

    // ---- IslandView.Host ----------------------------------------------------------------------

    override fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getRealSize(it) }
        }
    }

    override fun cutoutRect(): Rect? = try {
        @Suppress("DEPRECATION")
        val cutout = wm.defaultDisplay.cutout
        val top = cutout?.boundingRectTop
        if (top != null && !top.isEmpty) top else cutout?.boundingRects?.firstOrNull { !it.isEmpty }
    } catch (_: Throwable) {
        null
    }

    override fun attach() {
        if (attached) return
        runCatching { wm.addView(view, lp) }.onSuccess { attached = true }
    }

    override fun detach() {
        if (!attached) return
        attached = false
        runCatching { wm.removeView(view) }
    }

    override fun updateWindow(rect: Rect) {
        lp.x = rect.left
        lp.y = rect.top
        lp.width = maxOf(1, rect.width())
        lp.height = maxOf(1, rect.height())
        if (attached) runCatching { wm.updateViewLayout(view, lp) }
    }
}
