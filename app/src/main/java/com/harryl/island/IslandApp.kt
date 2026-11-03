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
    }
}
