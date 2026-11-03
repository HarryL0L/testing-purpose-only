package com.harryl.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager

/** What Android reports about the screen and its camera cutout, in screen pixels for the current rotation. */
data class IslandGeometry(
    val screenW: Int,
    val screenH: Int,
    val densityDpi: Int,
    val rotation: Int,
    val statusBarH: Int,
    val camera: CameraBox?,
    val source: Source,
) {
    enum class Source { CUTOUT_PATH, CUTOUT_RECT, NONE }

    companion object {
        fun detect(context: Context): IslandGeometry {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val wm = context.getSystemService(WindowManager::class.java)
            val size = screenSize(wm, display)
            var camera: CameraBox? = null
            var source = Source.NONE

            val cutout = runCatching { display?.cutout }.getOrNull()
            // Android decides which edge a cutout belongs to; only a top cutout counts as "the camera".
            val top = cutout?.boundingRectTop
            if (top != null && !top.isEmpty) {
                camera = CameraBox(top.left, top.top, top.right, top.bottom)
                source = Source.CUTOUT_RECT
                if (Build.VERSION.SDK_INT >= 31) {
                    // The cutout path is the exact camera shape; the bounding rect is often padded.
                    val path = runCatching { cutout.cutoutPath }.getOrNull()
                    if (path != null) {
                        val region = Region()
                        if (region.setPath(path, Region(top)) && !region.bounds.isEmpty) {
                            val b = region.bounds
                            camera = CameraBox(b.left, b.top, b.right, b.bottom)
                            source = Source.CUTOUT_PATH
                        }
                    }
                }
            }

            return IslandGeometry(
                screenW = size.x,
                screenH = size.y,
                densityDpi = context.resources.configuration.densityDpi,
                rotation = display?.rotation ?: 0,
                statusBarH = statusBarHeight(context, wm),
                camera = camera,
                source = source,
            )
        }

        private fun screenSize(wm: WindowManager?, display: Display?): Point {
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                val b = wm.maximumWindowMetrics.bounds
                return Point(b.width(), b.height())
            }
            val p = Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(p)
            return p
        }

        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        private fun statusBarHeight(context: Context, wm: WindowManager?): Int {
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                val h = runCatching {
                    wm.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                }.getOrDefault(0)
                if (h > 0) return h
            }
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * context.resources.displayMetrics.density).toInt()
        }
    }
}
