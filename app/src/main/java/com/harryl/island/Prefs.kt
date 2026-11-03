package com.harryl.island

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)

    var enabled by bool("enabled", false)
    var anchor by int("anchor", ANCHOR_AUTO)
    /** Fine-tune offsets in dp. */
    var offsetX by int("offset_x", 0)
    var offsetY by int("offset_y", 0)
    var collapseSeconds by int("collapse_seconds", 4)
    var persistentMusic by bool("persistent_music", true)
    var visualizer by bool("visualizer", true)
    var haptics by bool("haptics", true)
    var showNotifications by bool("show_notifications", true)
    var showDevices by bool("show_devices", true)
    var showCharging by bool("show_charging", true)

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
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
        const val ANCHOR_LEFT = 1
        const val ANCHOR_CENTER = 2
    }
}
