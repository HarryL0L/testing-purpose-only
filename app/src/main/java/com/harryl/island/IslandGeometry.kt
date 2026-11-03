package com.harryl.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/** What Android reports about the screen and its camera, in screen pixels for the current rotation. */
data class IslandGeometry(
    val screenW: Int,
    val screenH: Int,
    val densityDpi: Int,
    val rotation: Int,
    val statusBarH: Int,
    /** Best known camera hole. */
    val camera: CameraBox?,
    /** The raw top cutout Android reported, before any refinement. */
    val reported: CameraBox?,
    val source: Source,
) {
    enum class Source {
        /** User lined the ring up with the camera. */
        CALIBRATED,
        /** Exact cutout shape from Android 12+. */
        CUTOUT_PATH,
        /** Cutout bounding box, used as is. */
        CUTOUT_RECT,
        /** Corner-padded cutout box; hole estimated inside it. */
        ESTIMATED,
        NONE,
    }

    companion object {
        fun detect(context: Context, useCalibration: Boolean = true): IslandGeometry {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val wm = context.getSystemService(WindowManager::class.java)
            val size = screenSize(wm, display)
            val rotation = display?.rotation ?: Surface.ROTATION_0
            var camera: CameraBox? = null
            var reported: CameraBox? = null
            var source = Source.NONE

            val cutout = runCatching { display?.cutout }.getOrNull()
            // Android decides which edge a cutout belongs to; only a top cutout counts as "the camera".
            val top = cutout?.boundingRectTop
            if (top != null && !top.isEmpty) {
                reported = CameraBox(top.left, top.top, top.right, top.bottom)
                camera = reported
                source = Source.CUTOUT_RECT
                if (Build.VERSION.SDK_INT >= 31) {
                    // The cutout path can be the exact camera shape; the bounding box is often padded.
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
                val refined = IslandLayout.refineCornerCutout(camera!!, size.x)
                if (refined != camera) {
                    camera = refined
                    source = Source.ESTIMATED
                }
            }

            // The user's line-up wins, in portrait (the orientation it was made in).
            val prefs = Prefs(context)
            if (useCalibration && prefs.anchor == Prefs.ANCHOR_CALIBRATED && prefs.calibrated && rotation == Surface.ROTATION_0) {
                val w = size.x.toFloat()
                val d = (prefs.calD * w).roundToInt().coerceAtLeast(1)
                val cx = (prefs.calCx * w).roundToInt()
                val cy = (prefs.calCy * w).roundToInt()
                camera = CameraBox(cx - d / 2, cy - d / 2, cx - d / 2 + d, cy - d / 2 + d)
                source = Source.CALIBRATED
            }

            return IslandGeometry(
                screenW = size.x,
                screenH = size.y,
                densityDpi = context.resources.configuration.densityDpi,
                rotation = rotation,
                statusBarH = statusBarHeight(context, wm),
                camera = camera,
                reported = reported,
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
