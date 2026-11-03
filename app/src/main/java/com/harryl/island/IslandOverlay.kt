package com.harryl.island

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/**
 * Owns the overlay window, keeps it exactly as large as the island needs, and re-fits the island
 * whenever the display changes (rotation, resolution, display size, cutout).
 *
 * [windowType] is TYPE_ACCESSIBILITY_OVERLAY when the accessibility service is on (drawn above
 * the status bar, so taps work) or TYPE_APPLICATION_OVERLAY otherwise.
 */
class IslandOverlay(
    hostContext: Context,
    private val windowType: Int,
    private val prefs: Prefs,
    private val listener: IslandView.Listener,
) : IslandView.Host {

    private val handler = Handler(Looper.getMainLooper())
    private val windowContext: Context =
        if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) overlayContext(hostContext) else hostContext
    private val viewContext = ContextThemeWrapper(windowContext, R.style.Theme_Mochi)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val displayManager = hostContext.getSystemService(DisplayManager::class.java)

    private var geometry = IslandGeometry.detect(windowContext)
    private var view = IslandView(viewContext, prefs, this, listener)
    private var attached = false
    private var last: Triple<IslandContent, IslandContent?, Boolean>? = null

    private val lp = WindowManager.LayoutParams().apply {
        type = windowType
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
        title = "Mochi"
        width = 1
        height = 1
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            // Also fires on 120/60 Hz switches; relayout() is a no-op when nothing moved.
            if (displayId == Display.DEFAULT_DISPLAY) relayout()
        }
    }

    init {
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    fun present(primary: IslandContent, companion: IslandContent?, expanded: Boolean) {
        last = Triple(primary, companion, expanded)
        view.present(primary, companion, expanded)
    }

    fun hide() {
        last = null
        view.dismiss()
    }

    /** Re-reads the screen and camera from Android and re-fits the island if anything changed. */
    fun relayout() {
        val g = IslandGeometry.detect(windowContext)
        val densityChanged = g.densityDpi != geometry.densityDpi
        geometry = g
        if (densityChanged) recreateView() else view.relayout()
    }

    fun destroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        view.cancelAll()
        detach()
    }

    /** Text sizes and dp values are baked into the view, so a display-size change rebuilds it. */
    private fun recreateView() {
        view.cancelAll()
        detach()
        view = IslandView(viewContext, prefs, this, listener)
        last?.let { (p, c, e) -> view.present(p, c, e) }
    }

    // ---- IslandView.Host ----------------------------------------------------------------------

    override fun geometry(): IslandGeometry = geometry

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

    private fun overlayContext(ctx: Context): Context {
        if (Build.VERSION.SDK_INT < 30) return ctx
        return try {
            val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ctx.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (_: Throwable) {
            ctx
        }
    }
}
