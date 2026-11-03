package com.harryl.island

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Material 3 Expressive grouped list: the rows of a section share one rounded container. The
 * first and last rows get large outer corners, rows in between get small inner corners, and a
 * thin gap separates them, like the settings screens in Android 16.
 */
object ExpressiveList {

    fun style(group: ViewGroup) {
        val d = group.resources.displayMetrics.density
        val outer = 24 * d
        val inner = 6 * d
        val rows = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility != View.GONE }
        val fill = MaterialColors.getColor(group, com.google.android.material.R.attr.colorSurfaceContainer)
        val ripple = MaterialColors.getColor(group, androidx.appcompat.R.attr.colorControlHighlight)
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) outer else inner
            val bottom = if (i == rows.lastIndex) outer else inner
            val shape = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(top).setTopRightCornerSize(top)
                .setBottomLeftCornerSize(bottom).setBottomRightCornerSize(bottom)
                .build()
            val bg = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(fill) }
            row.background = RippleDrawable(ColorStateList.valueOf(ripple), bg, MaterialShapeDrawable(shape))
            (row.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = if (i == 0) 0 else (2 * d).toInt()
                row.layoutParams = it
            }
        }
    }
}
