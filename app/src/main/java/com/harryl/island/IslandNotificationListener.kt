package com.harryl.island

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

/**
 * Feeds app notifications into the island and gives the app the notification-access grant
 * that MediaSessionManager needs. Also acts as a reliable auto-start hook: the system binds
 * this service at boot, so the island restarts even if Realme UI blocks BOOT_COMPLETED.
 */
class IslandNotificationListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    /** key -> last title|text we surfaced, to ignore silent re-posts of the same notification. */
    private val seen = object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 64
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        if (prefs.enabled && !IslandService.running) IslandService.start(this)
        IslandBus.post(IslandEvent.ListenerConnected)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!prefs.showNotifications || !IslandService.running) return
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (!sbn.isClearable) return // ongoing / foreground-service notifications
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // handled by MediaWatcher
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return
        if (isSilent(sbn, rankingMap)) return

        val extras = n.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val signature = (title + "\u0000" + text).hashCode()
        if (seen[sbn.key] == signature) return
        seen[sbn.key] = signature

        IslandBus.post(
            IslandEvent.Transient(
                IslandContent.Notice(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appLabel(sbn.packageName),
                    title = title.ifEmpty { appLabel(sbn.packageName) },
                    text = text,
                    icon = loadIcon(sbn),
                    contentIntent = n.contentIntent,
                ),
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        IslandBus.post(IslandEvent.DismissNotice(sbn.key))
    }

    private fun isSilent(sbn: StatusBarNotification, map: RankingMap?): Boolean {
        val ranking = Ranking()
        val m = map ?: currentRanking
        if (m.getRanking(sbn.key, ranking)) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return true
            if (ranking.isAmbient) return true
        }
        return false
    }

    private fun loadIcon(sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification
        val large: Drawable? = runCatching { n.getLargeIcon()?.loadDrawable(this) }.getOrNull()
        val d = large ?: runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull() ?: return null
        return runCatching { d.toBitmap(ICON_PX, ICON_PX) }.getOrNull()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val ICON_PX = 96
    }
}
