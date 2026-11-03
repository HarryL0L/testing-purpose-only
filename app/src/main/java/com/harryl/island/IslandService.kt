package com.harryl.island

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the island overlay. It holds three pieces of state:
 *  - the ongoing music session (shown as a compact pill),
 *  - the set of connected audio devices (shown as a companion bubble next to the music pill),
 *  - one transient item (notification / device connected / charger) that expands for a few seconds.
 */
class IslandService : Service(), IslandView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var overlay: IslandOverlay
    private lateinit var media: MediaWatcher
    private lateinit var devices: DeviceReceiver
    private val handler = Handler(Looper.getMainLooper())

    private var music: IslandContent.Music? = null
    private var musicDismissedKey: String? = null
    private var pausedAt = 0L
    private val connected = LinkedHashMap<String, IslandContent.Device>()
    private var transient: IslandContent? = null
    private var screenOn = true
    private var userExpanded = false

    private val collapseRunnable = Runnable {
        transient = null
        userExpanded = false
        render()
    }
    private val reevaluate = Runnable { render() }

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        startInForeground()
        overlay = IslandOverlay(this, prefs, this)
        media = MediaWatcher(this) { onMusic(it) }
        media.start()
        devices = DeviceReceiver(this, prefs) { IslandBus.post(it) }
        devices.register()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        IslandBus.attach { onEvent(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PREVIEW -> intent.getStringExtra(EXTRA_PREVIEW)?.let { preview(it) }
            ACTION_RELAYOUT -> overlay.relayout()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        IslandBus.detach()
        devices.unregister()
        media.stop()
        overlay.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.relayout()
    }

    // ---- Events -------------------------------------------------------------------------------

    private fun onEvent(e: IslandEvent) {
        when (e) {
            is IslandEvent.Music -> onMusic(e.music)
            is IslandEvent.Transient -> showTransient(e.content)
            is IslandEvent.DismissNotice -> {
                val t = transient
                if (t is IslandContent.Notice && t.key == e.key) collapseRunnable.run()
            }
            is IslandEvent.DeviceBattery -> onDeviceBattery(e.address, e.battery)
            is IslandEvent.Screen -> {
                screenOn = e.on
                render()
            }
            IslandEvent.ListenerConnected -> media.start().also { media.refresh() }
        }
    }

    private fun onMusic(m: IslandContent.Music?) {
        val old = music
        music = m
        if (m != null && old != null && m.trackKey != old.trackKey) musicDismissedKey = null
        if (m != null && !m.playing && (old == null || old.playing || old.trackKey != m.trackKey)) {
            pausedAt = SystemClock.elapsedRealtime()
        }
        render()
    }

    private fun showTransient(content: IslandContent) {
        when (content) {
            is IslandContent.Device -> {
                if (content.address != "wired") {
                    if (content.connected) connected[content.address] = content else connected.remove(content.address)
                }
            }
            else -> Unit
        }
        transient = content
        userExpanded = false
        handler.removeCallbacks(collapseRunnable)
        val extra = if (content is IslandContent.Notice) 1500L else 0L
        handler.postDelayed(collapseRunnable, prefs.collapseSeconds * 1000L + extra)
        if (screenOn && prefs.haptics) tick()
        render()
    }

    private fun onDeviceBattery(address: String, battery: Int) {
        val existing = connected[address]
        if (existing != null) connected[address] = existing.copy(battery = battery)
        val t = transient
        if (t is IslandContent.Device && t.address == address && t.battery != battery) {
            transient = t.copy(battery = battery)
        }
        render()
    }

    // ---- Rendering ----------------------------------------------------------------------------

    private fun currentMusic(): IslandContent.Music? {
        val m = music ?: return null
        if (m.trackKey == musicDismissedKey) return null
        if (!m.playing) {
            if (!prefs.persistentMusic) return null
            val age = SystemClock.elapsedRealtime() - pausedAt
            if (age >= PAUSED_GRACE_MS) return null
            handler.removeCallbacks(reevaluate)
            handler.postDelayed(reevaluate, PAUSED_GRACE_MS - age + 50)
        }
        return m
    }

    private fun companionDevice(): IslandContent.Device? =
        connected.values.lastOrNull { it.battery >= 0 } ?: connected.values.lastOrNull()

    private fun render() {
        if (!screenOn) {
            overlay.hide()
            return
        }
        val t = transient
        val m = currentMusic()
        when {
            t != null -> overlay.present(t, null, expanded = true)
            m != null -> overlay.present(m, companionDevice(), expanded = userExpanded)
            else -> overlay.hide()
        }
    }

    private fun tick() {
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    // ---- IslandView.Listener ------------------------------------------------------------------

    override fun onTapCompact(content: IslandContent) {
        if (content is IslandContent.Music) {
            userExpanded = true
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, EXPANDED_MUSIC_MS)
            render()
        }
    }

    override fun onTapExpanded(content: IslandContent) {
        open(content)
        collapseRunnable.run()
    }

    override fun onTapCompanion(content: IslandContent) {
        showTransient(content)
    }

    override fun onSwipeUp(content: IslandContent) {
        if (content is IslandContent.Music && transient == null) {
            musicDismissedKey = content.trackKey
            userExpanded = false
            render()
        } else {
            collapseRunnable.run()
        }
    }

    override fun onMediaControl(action: Int) {
        when (action) {
            IslandView.MEDIA_PREVIOUS -> media.previous()
            IslandView.MEDIA_PLAY_PAUSE -> media.playPause()
            IslandView.MEDIA_NEXT -> media.next()
        }
        if (userExpanded) {
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, EXPANDED_MUSIC_MS)
        }
    }

    override fun onOutsideTouch() {
        if (transient != null || userExpanded) collapseRunnable.run()
    }

    private fun open(content: IslandContent) {
        try {
            when (content) {
                is IslandContent.Music -> packageManager.getLaunchIntentForPackage(content.packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is IslandContent.Notice -> {
                    val pi = content.contentIntent
                    if (pi != null) sendPendingIntent(pi)
                    else packageManager.getLaunchIntentForPackage(content.packageName)?.let {
                        startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                is IslandContent.Device -> startActivity(
                    Intent(if (content.kind == DeviceKind.WIRED) Settings.ACTION_SOUND_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                is IslandContent.Power -> startActivity(
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun sendPendingIntent(pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(this, 0, null, null, null, null, options.toBundle())
        } else {
            pi.send()
        }
    }

    // ---- Preview ------------------------------------------------------------------------------

    private fun preview(kind: String) {
        if (!screenOn) return
        when (kind) {
            PREVIEW_MUSIC -> {
                val art = previewArt()
                val m = IslandContent.Music(
                    packageName = packageName, title = "Blinding Lights", artist = "The Weeknd",
                    art = art, playing = true, position = 42_000, duration = 200_000,
                    positionAt = SystemClock.elapsedRealtime(), speed = 1f, accent = 0xFFE0477B.toInt(),
                )
                showTransient(m)
            }
            PREVIEW_EARBUDS -> showTransient(IslandContent.Device("preview:wf", DeviceKind.EARBUDS, "WF-C500", 82, true))
            PREVIEW_WIRED -> showTransient(IslandContent.Device("wired", DeviceKind.WIRED, getString(R.string.wired_headphones), -1, true))
            PREVIEW_NOTICE -> showTransient(
                IslandContent.Notice(
                    "preview", packageName, getString(R.string.app_name), "Realme Island",
                    "This is how a notification looks. Tap to open, swipe up to dismiss.", null, null,
                ),
            )
            PREVIEW_CHARGING -> showTransient(IslandContent.Power(64, true))
        }
    }

    private fun previewArt(): Bitmap {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 160f, 160f, 0xFFE0477B.toInt(), 0xFF4D5BFF.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, 160f, 160f, p)
        return b
    }

    // ---- Foreground ---------------------------------------------------------------------------

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island)
            .setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "island_service"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.harryl.island.action.STOP"
        const val ACTION_PREVIEW = "com.harryl.island.action.PREVIEW"
        const val ACTION_RELAYOUT = "com.harryl.island.action.RELAYOUT"
        const val EXTRA_PREVIEW = "preview"
        const val PREVIEW_MUSIC = "music"
        const val PREVIEW_EARBUDS = "earbuds"
        const val PREVIEW_WIRED = "wired"
        const val PREVIEW_NOTICE = "notice"
        const val PREVIEW_CHARGING = "charging"

        private const val PAUSED_GRACE_MS = 30_000L
        private const val EXPANDED_MUSIC_MS = 7_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandService::class.java))
        }

        fun preview(context: Context, kind: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, IslandService::class.java).setAction(ACTION_PREVIEW).putExtra(EXTRA_PREVIEW, kind),
            )
        }

        fun relayout(context: Context) {
            if (!running) return
            ContextCompat.startForegroundService(
                context, Intent(context, IslandService::class.java).setAction(ACTION_RELAYOUT),
            )
        }
    }
}
