package com.harryl.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Normal app overlays sit *below* the status bar, so the status bar swallows every tap on the
 * island. An accessibility overlay sits above it, which is what makes tap-to-expand, media buttons
 * and swipe-to-dismiss work. This service reads no screen content: it only listens for window
 * changes to hide the island while the notification shade or lock screen covers the screen.
 */
class IslandAccessibilityService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private val bounds = Rect()
    private var shadeOpen = false

    override fun onServiceConnected() {
        prefs = Prefs(this)
        instance = this
        if (!prefs.enabled) return
        if (IslandService.running) IslandBus.post(IslandEvent.HostChanged) else IslandService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!IslandService.running) return
        val open = shadeVisible()
        if (open != shadeOpen) {
            shadeOpen = open
            IslandBus.post(IslandEvent.Shade(open))
        }
    }

    /** The notification shade / keyguard is a system window covering most of the screen. */
    private fun shadeVisible(): Boolean {
        val list = try {
            windows
        } catch (_: Exception) {
            return false
        }
        val screenH = resources.displayMetrics.heightPixels
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(bounds)
            if (bounds.height() > screenH / 2) return true
        }
        return false
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance !== this) return
        instance = null
        if (shadeOpen) IslandBus.post(IslandEvent.Shade(false))
        IslandBus.post(IslandEvent.HostChanged)
    }

    companion object {
        @Volatile
        var instance: IslandAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, IslandAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
