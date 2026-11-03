package com.harryl.island

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class IslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            IslandService.CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
        migrateNudges()
    }

    /**
     * Estimate revision 2 moved the corner-box hole down by a sixth of the box height, which is
     * what people nudged by hand on revision 1. Clear those nudges once so they don't apply twice.
     */
    private fun migrateNudges() {
        val prefs = Prefs(this)
        if (prefs.estimateRev >= ESTIMATE_REV) return
        prefs.offsetX = 0
        prefs.offsetY = 0
        prefs.estimateRev = ESTIMATE_REV
    }

    companion object {
        const val ESTIMATE_REV = 2
    }
}
