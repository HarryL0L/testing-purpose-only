package com.harryl.island

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

/**
 * Feeds app notifications and live activities (stopwatch, timer, call, download, navigation) into
 * the island, and gives the app the notification-access grant MediaSessionManager needs. The system
 * binds this service at boot, so it also restarts the island when Realme UI blocks BOOT_COMPLETED.
 */
class IslandNotificationListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    /** key -> last title|text we surfaced, to ignore silent re-posts of the same notification. */
    private val seen = object : LinkedHashMap<String, Int>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 64
    }
    private val liveKeys = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        instance = this
        if (prefs.enabled && !IslandService.running) IslandService.start(this)
        IslandBus.post(IslandEvent.ListenerConnected)
        resync()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        liveKeys.forEach { IslandBus.post(IslandEvent.LiveRemoved(it)) }
        liveKeys.clear()
    }

    /** Re-publish ongoing live activities, e.g. a stopwatch that was already running. */
    fun resync() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) toLive(sbn)?.let { publishLive(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName == packageName) return
        val live = toLive(sbn)
        if (live != null) {
            publishLive(live)
            return
        }
        if (liveKeys.remove(sbn.key)) IslandBus.post(IslandEvent.LiveRemoved(sbn.key))
        onNotice(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
        if (liveKeys.remove(sbn.key)) IslandBus.post(IslandEvent.LiveRemoved(sbn.key))
        IslandBus.post(IslandEvent.DismissNotice(sbn.key))
    }

    private fun publishLive(live: IslandContent.Live) {
        if (!prefs.showLive) return
        liveKeys += live.key
        IslandBus.post(IslandEvent.Live(live))
    }

    // ---- Regular notifications ----------------------------------------------------------------

    private fun onNotice(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!prefs.showNotifications || !IslandService.running) return
        val n = sbn.notification
        if (!sbn.isClearable) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (isMedia(n)) return
        if (isSilent(sbn, rankingMap)) return

        val title = title(n)
        val text = text(n)
        if (title.isEmpty() && text.isEmpty()) return

        val signature = (title + "\u0000" + text).hashCode()
        val previous = seen[sbn.key]
        if (previous == signature) return
        // Updates to a notification the app marked "alert once" should not pop the island again.
        if (previous != null && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) {
            seen[sbn.key] = signature
            return
        }
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

    // ---- Live activities ----------------------------------------------------------------------

    private fun toLive(sbn: StatusBarNotification): IslandContent.Live? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (isMedia(n)) return null

        val extras = n.extras
        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = extras.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN)
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val progress = if (max > 0 && !indeterminate) (extras.getInt(Notification.EXTRA_PROGRESS) * 100 / max).coerceIn(0, 100) else -1
        val category = n.category
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val clockApp = sbn.packageName in CLOCK_PACKAGES

        val kind = when {
            category == Notification.CATEGORY_CALL || template.contains("CallStyle") -> LiveKind.CALL
            category == CATEGORY_STOPWATCH -> LiveKind.STOPWATCH
            countDown || category == Notification.CATEGORY_ALARM -> LiveKind.TIMER
            category == Notification.CATEGORY_NAVIGATION -> LiveKind.NAVIGATION
            progress >= 0 || category == Notification.CATEGORY_PROGRESS -> LiveKind.PROGRESS
            clockApp -> if (looksLikeTimer(n)) LiveKind.TIMER else LiveKind.STOPWATCH
            chrono -> LiveKind.OTHER
            else -> return null
        }

        val title = title(n)
        val text = text(n)
        if (title.isEmpty() && text.isEmpty() && !chrono) return null
        val label = appLabel(sbn.packageName)
        val accent = if (n.color != Notification.COLOR_DEFAULT && n.color != 0) n.color or 0xFF000000.toInt()
        else ContextCompat.getColor(this, R.color.island_accent)

        return IslandContent.Live(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = label,
            title = title.ifEmpty { label },
            text = text,
            icon = loadIcon(sbn),
            kind = kind,
            chronoBase = if (chrono && n.`when` > 0) n.`when` else 0L,
            countDown = countDown,
            progress = progress,
            accent = accent,
            contentIntent = n.contentIntent,
        )
    }

    private fun looksLikeTimer(n: Notification): Boolean {
        val s = (title(n) + " " + text(n)).lowercase()
        return s.contains("timer") || s.contains("remaining") || s.contains("left")
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun isMedia(n: Notification): Boolean =
        n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true

    private fun title(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
            ?.toString()?.trim().orEmpty()

    private fun text(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()

    private fun isSilent(sbn: StatusBarNotification, map: RankingMap?): Boolean {
        val ranking = Ranking()
        val m = map ?: currentRanking ?: return false
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
        private const val CATEGORY_STOPWATCH = "stopwatch"
        private const val EXTRA_CHRONOMETER_COUNT_DOWN = "android.chronometerCountDown"

        /** Clock apps whose ongoing notifications are always a running stopwatch or timer. */
        private val CLOCK_PACKAGES = setOf(
            "com.coloros.alarmclock", // Realme UI / ColorOS
            "com.oplus.alarmclock",
            "com.realme.alarmclock",
            "com.oneplus.deskclock",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.miui.clock",
            "com.android.BBKClock",
        )

        @Volatile
        var instance: IslandNotificationListener? = null
            private set
    }
}
