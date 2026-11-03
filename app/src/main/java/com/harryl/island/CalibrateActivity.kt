package com.harryl.island

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/** One-time screen to line a ring up with the real camera hole. Hides the island while open. */
class CalibrateActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var canvas: CalibrateView
    private lateinit var sizeSlider: Slider
    private lateinit var readout: TextView
    private var screenW = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Draw under the status bar and into the camera cutout, with the system bars hidden.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val g = IslandGeometry.detect(this, useCalibration = false)
        screenW = g.screenW
        val d = resources.displayMetrics.density
        canvas = CalibrateView(this)
        // Start from the saved line-up, else the automatic estimate, else a sensible corner guess.
        if (prefs.calibrated) {
            canvas.cx = prefs.calCx * screenW
            canvas.cy = prefs.calCy * screenW
            canvas.diameter = prefs.calD * screenW
        } else {
            val cam = g.camera?.let { IslandLayout.usableCamera(it, g.screenW, g.screenH) }
            canvas.cx = cam?.let { (it.left + it.right) / 2f } ?: (32 * d)
            canvas.cy = cam?.let { (it.top + it.bottom) / 2f } ?: (g.statusBarH / 2f)
            canvas.diameter = cam?.let { minOf(it.width, it.height).toFloat() } ?: (18 * d)
        }

        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(buildPanel(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = (12 * d).roundToInt()
            setMargins(m, m, m, m + (24 * d).roundToInt())
        })
        setContentView(root)
        canvas.onChange = { syncControls() }
        syncControls()
    }

    private fun buildPanel(): MaterialCardView {
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        val card = MaterialCardView(this).apply { radius = 20 * d }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(12))
        }
        col.addView(TextView(this).apply {
            setText(R.string.cal_title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        })
        col.addView(TextView(this).apply {
            setText(R.string.cal_help)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, px(4), 0, px(8))
        })
        readout = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        }
        col.addView(readout)
        sizeSlider = Slider(this).apply {
            valueFrom = canvas.minDiameter
            valueTo = canvas.maxDiameter
            value = canvas.diameter.coerceIn(valueFrom, valueTo)
            addOnChangeListener { _, v, fromUser -> if (fromUser) canvas.setSize(v) }
        }
        col.addView(TextView(this).apply {
            setText(R.string.cal_size)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setPadding(0, px(8), 0, 0)
        })
        col.addView(sizeSlider)

        val arrows = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        fun arrow(label: String, dx: Float, dy: Float) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            minWidth = px(56)
            setOnClickListener { canvas.nudge(dx, dy) }
            setOnLongClickListener { canvas.nudge(dx * 5, dy * 5); true }
        }
        arrows.addView(arrow("←", -1f, 0f))
        arrows.addView(arrow("↑", 0f, -1f))
        arrows.addView(arrow("↓", 0f, 1f))
        arrows.addView(arrow("→", 1f, 0f))
        col.addView(arrows)

        val actions = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(0, px(8), 0, 0)
        }
        actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.cal_use_auto)
            setOnClickListener {
                prefs.anchor = Prefs.ANCHOR_AUTO
                finishAndPreview()
            }
        })
        actions.addView(MaterialButton(this).apply {
            setText(R.string.cal_save)
            setOnClickListener { save() }
        })
        col.addView(actions)
        card.addView(col)
        return card
    }

    private fun syncControls() {
        val d = resources.displayMetrics.density
        if (sizeSlider.value != canvas.diameter.coerceIn(sizeSlider.valueFrom, sizeSlider.valueTo)) {
            sizeSlider.value = canvas.diameter.coerceIn(sizeSlider.valueFrom, sizeSlider.valueTo)
        }
        readout.text = getString(
            R.string.cal_readout,
            canvas.cx.roundToInt(), canvas.cy.roundToInt(), canvas.diameter.roundToInt(), canvas.diameter / d,
        )
    }

    private fun save() {
        val w = screenW.toFloat()
        prefs.calCx = canvas.cx / w
        prefs.calCy = canvas.cy / w
        prefs.calD = canvas.diameter / w
        prefs.calibrated = true
        prefs.anchor = Prefs.ANCHOR_CALIBRATED
        // The line-up is exact, so old nudges would only push it off again.
        prefs.offsetX = 0
        prefs.offsetY = 0
        finishAndPreview()
    }

    private fun finishAndPreview() {
        IslandService.setCalibrating(this, false)
        IslandService.relayout(this)
        IslandService.preview(this, IslandService.PREVIEW_MUSIC)
        finish()
    }

    override fun onResume() {
        super.onResume()
        IslandService.setCalibrating(this, true)
    }

    override fun onPause() {
        super.onPause()
        // Also covers leaving with Back, so the island always comes back.
        IslandService.setCalibrating(this, false)
    }
}
