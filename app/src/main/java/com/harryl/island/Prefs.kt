package com.harryl.island

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)

    var enabled by bool("enabled", false)
    /** [ANCHOR_AUTO] follows the camera cutout, [ANCHOR_CALIBRATED] uses the user's line-up, [ANCHOR_CENTER] forces top centre. */
    var anchor by int("anchor", ANCHOR_AUTO)
    /** Optional nudges in dp on top of the detected camera position. */
    var offsetX by int("offset_x", 0)
    var offsetY by int("offset_y", 0)
    var collapseSeconds by int("collapse_seconds", 4)
    var persistentMusic by bool("persistent_music", true)
    var visualizer by bool("visualizer", true)
    var haptics by bool("haptics", true)
    var showNotifications by bool("show_notifications", true)
    var showLive by bool("show_live", true)
    var showDevices by bool("show_devices", true)
    var showCharging by bool("show_charging", true)
    var hideWithShade by bool("hide_with_shade", true)

    /** Camera lined up by the user, as fractions of the portrait screen width (survives resolution changes). */
    var calibrated by bool("cal_set", false)
    var calCx by float("cal_cx", 0f)
    var calCy by float("cal_cy", 0f)
    var calD by float("cal_d", 0f)

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun float(key: String, def: Float) = object : ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getFloat(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            sp.edit().putFloat(key, value).apply()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    companion object {
        const val ANCHOR_AUTO = 0
        /** Old "fixed left hole" setting; now treated as auto. */
        const val ANCHOR_LEFT_LEGACY = 1
        const val ANCHOR_CENTER = 2
        const val ANCHOR_CALIBRATED = 3
    }
}
