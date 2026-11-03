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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the island overlay. State:
 *  - live activities (stopwatch, timer, call, download, navigation) shown as a persistent pill,
 *  - the music session, shown as a pill when no live activity is running,
 *  - connected audio devices (real connection state), shown as a companion bubble,
 *  - one transient item (notification, device change, charger) that expands for a few seconds.
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
    private val lives = LinkedHashMap<String, IslandContent.Live>()
    private val dismissedLives = HashSet<String>()
    private var transient: IslandContent? = null
    private var screenOn = true
    private var shadeOpen = false
    /** The line-up screen is open; the island must not cover the camera. */
    private var calibrating = false
    /** [IslandContent.expandKey] of the pill the user tapped open, if any. */
    private var expandedKey: String? = null

    private var lastHapticAt = 0L
    private var lastHapticKey: String? = null

    private val collapseRunnable = Runnable {
        transient = null
        expandedKey = null
        render()
    }
    private val reevaluate = Runnable { render() }

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        startInForeground()
        overlay = buildOverlay()
        media = MediaWatcher(this) { onMusic(it) }
        media.start()
        devices = DeviceReceiver(this, prefs) { IslandBus.post(it) }
        devices.register()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        IslandBus.attach { onEvent(it) }
        IslandNotificationListener.instance?.resync()
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
            ACTION_CALIBRATING -> {
                calibrating = intent.getBooleanExtra(EXTRA_ON, false)
                if (calibrating) collapseRunnable.run() else render()
            }
            ACTION_RESYNC -> {
                devices.seedBluetooth()
                IslandNotificationListener.instance?.resync()
            }
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

    private fun buildOverlay(): IslandOverlay {
        val a11y = IslandAccessibilityService.instance
        return if (a11y != null) {
            IslandOverlay(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, prefs, this)
        } else {
            IslandOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, prefs, this)
        }
    }

    private fun recreateOverlay() {
        overlay.destroy()
        overlay = buildOverlay()
        render()
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
            is IslandEvent.Live -> onLive(e.live)
            is IslandEvent.LiveRemoved -> onLiveRemoved(e.key)
            is IslandEvent.DeviceState -> onDeviceState(e.device, e.announce)
            is IslandEvent.DeviceBattery -> onDeviceBattery(e.address, e.battery)
            IslandEvent.BluetoothOff -> {
                connected.keys.removeAll { it != DeviceReceiver.WIRED_ADDRESS }
                render()
            }
            is IslandEvent.Screen -> {
                screenOn = e.on
                render()
            }
            is IslandEvent.Shade -> {
                shadeOpen = e.open
                render()
            }
            IslandEvent.HostChanged -> recreateOverlay()
            IslandEvent.ListenerConnected -> {
                media.start()
                media.refresh()
            }
        }
    }

    private fun onMusic(m: IslandContent.Music?) {
        val old = music
        music = m
        if (m != null && old != null && m.trackKey != old.trackKey) musicDismissedKey = null
        if (m != null && !m.playing && (old == null || old.playing || old.trackKey != m.trackKey)) {
            pausedAt = SystemClock.elapsedRealtime()
        }
        // Keep an expanded music card (opened from the companion bubble) in sync.
        val t = transient
        if (t is IslandContent.Music && m != null && t.packageName == m.packageName) transient = m
        render()
    }

    private fun onLive(l: IslandContent.Live) {
        if (!prefs.showLive) return
        val isNew = !lives.containsKey(l.key)
        lives[l.key] = l // replacing an existing key keeps its position
        if (isNew) {
            dismissedLives.remove(l.key)
            // Like a starting Live Activity: show it open for a moment, then settle into the pill.
            if (transient == null) {
                expandedKey = l.expandKey
                handler.removeCallbacks(collapseRunnable)
                handler.postDelayed(collapseRunnable, prefs.collapseSeconds * 1000L)
            }
            haptic(l.expandKey)
        }
        render()
    }

    private fun onLiveRemoved(key: String) {
        val removed = lives.remove(key) ?: return
        dismissedLives.remove(key)
        if (expandedKey == removed.expandKey) expandedKey = null
        render()
    }

    private fun onDeviceState(d: IslandContent.Device, announce: Boolean) {
        val known = connected.containsKey(d.address)
        if (d.connected) {
            val prev = connected.remove(d.address)
            connected[d.address] = if (prev != null && d.battery < 0) d.copy(battery = prev.battery) else d
        } else {
            connected.remove(d.address)
        }
        // Only real changes are announced: no double pop for A2DP + HFP, nothing for the initial scan.
        if (announce && prefs.showDevices && d.connected != known) showTransient(connected[d.address] ?: d) else render()
    }

    private fun onDeviceBattery(address: String, battery: Int) {
        val existing = connected[address] ?: return
        if (existing.battery == battery) return
        connected[address] = existing.copy(battery = battery)
        val t = transient
        if (t is IslandContent.Device && t.address == address) transient = t.copy(battery = battery)
        render()
    }

    private fun showTransient(content: IslandContent) {
        transient = content
        expandedKey = null
        handler.removeCallbacks(collapseRunnable)
        val extra = if (content is IslandContent.Notice) 1500L else 0L
        handler.postDelayed(collapseRunnable, prefs.collapseSeconds * 1000L + extra)
        haptic(content.expandKey)
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

    private fun currentLive(): IslandContent.Live? {
        val visible = lives.values.filter { it.key !in dismissedLives }
        return visible.lastOrNull { it.kind == LiveKind.CALL } ?: visible.lastOrNull()
    }

    private fun companionDevice(): IslandContent.Device? = connected.values.lastOrNull()

    private fun render() {
        if (!screenOn || calibrating || (shadeOpen && prefs.hideWithShade)) {
            overlay.hide()
            return
        }
        val t = transient
        if (t != null) {
            overlay.present(t, null, expanded = true)
            return
        }
        val live = currentLive()
        val m = currentMusic()
        val primary: IslandContent = live ?: m ?: run {
            overlay.hide()
            return
        }
        val companion: IslandContent? = if (primary is IslandContent.Live) (m ?: companionDevice()) else companionDevice()
        overlay.present(primary, companion, expanded = expandedKey != null && expandedKey == primary.expandKey)
    }

    /** One short tick per genuinely new item, never more than one every 1.5 s, never for updates. */
    private fun haptic(key: String) {
        if (!screenOn || !prefs.haptics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHapticAt < 1_500) return
        if (key == lastHapticKey && now - lastHapticAt < 10_000) return
        lastHapticAt = now
        lastHapticKey = key
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    // ---- IslandView.Listener ------------------------------------------------------------------

    override fun onTapCompact(content: IslandContent) {
        if (content is IslandContent.Music || content is IslandContent.Live) {
            expandedKey = content.expandKey
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, EXPANDED_MS)
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
        when {
            transient != null || expandedKey != null -> collapseRunnable.run()
            content is IslandContent.Music -> {
                musicDismissedKey = content.trackKey
                render()
            }
            content is IslandContent.Live -> {
                dismissedLives += content.key
                render()
            }
        }
    }

    override fun onMediaControl(action: Int) {
        when (action) {
            IslandView.MEDIA_PREVIOUS -> media.previous()
            IslandView.MEDIA_PLAY_PAUSE -> media.playPause()
            IslandView.MEDIA_NEXT -> media.next()
        }
        if (expandedKey != null) {
            handler.removeCallbacks(collapseRunnable)
            handler.postDelayed(collapseRunnable, EXPANDED_MS)
        }
    }

    override fun onOutsideTouch() {
        if (transient != null || expandedKey != null) collapseRunnable.run()
    }

    private fun open(content: IslandContent) {
        try {
            when (content) {
                is IslandContent.Music -> launch(content.packageName)
                is IslandContent.Live -> content.contentIntent?.let { sendPendingIntent(it) } ?: launch(content.packageName)
                is IslandContent.Notice -> content.contentIntent?.let { sendPendingIntent(it) } ?: launch(content.packageName)
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

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
    // Previews are transient only: they never touch the real device / live / music state.

    private fun preview(kind: String) {
        if (!screenOn || calibrating) return
        when (kind) {
            PREVIEW_MUSIC -> showTransient(
                IslandContent.Music(
                    packageName = packageName, title = "Blinding Lights", artist = "The Weeknd",
                    art = previewArt(), playing = true, position = 42_000, duration = 200_000,
                    positionAt = SystemClock.elapsedRealtime(), speed = 1f, accent = 0xFFE0477B.toInt(),
                ),
            )
            PREVIEW_EARBUDS -> showTransient(IslandContent.Device("preview:wf", DeviceKind.EARBUDS, "WF-C500", 82, true))
            PREVIEW_WIRED -> showTransient(
                IslandContent.Device("preview:wired", DeviceKind.WIRED, getString(R.string.wired_headphones), -1, true),
            )
            PREVIEW_NOTICE -> showTransient(
                IslandContent.Notice(
                    "preview", packageName, getString(R.string.app_name), "Realme Island",
                    "This is how a notification looks. Tap to open, swipe up to dismiss.", null, null,
                ),
            )
            PREVIEW_CHARGING -> showTransient(IslandContent.Power(64, true))
            PREVIEW_STOPWATCH -> showTransient(
                IslandContent.Live(
                    key = "preview:live", packageName = packageName, appName = "Clock", title = "Stopwatch",
                    text = "Running", icon = null, kind = LiveKind.STOPWATCH,
                    chronoBase = System.currentTimeMillis() - 83_000, countDown = false, progress = -1,
                    accent = 0xFFFF9F0A.toInt(), contentIntent = null,
                ),
            )
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
        const val ACTION_RESYNC = "com.harryl.island.action.RESYNC"
        const val ACTION_CALIBRATING = "com.harryl.island.action.CALIBRATING"
        const val EXTRA_ON = "on"
        const val EXTRA_PREVIEW = "preview"
        const val PREVIEW_MUSIC = "music"
        const val PREVIEW_EARBUDS = "earbuds"
        const val PREVIEW_WIRED = "wired"
        const val PREVIEW_NOTICE = "notice"
        const val PREVIEW_CHARGING = "charging"
        const val PREVIEW_STOPWATCH = "stopwatch"

        private const val PAUSED_GRACE_MS = 30_000L
        private const val EXPANDED_MS = 7_000L

        @Volatile
        var running = false
            private set

        fun canShow(context: Context): Boolean =
            Settings.canDrawOverlays(context) || IslandAccessibilityService.isEnabled(context)

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, IslandService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IslandService::class.java))
        }

        fun preview(context: Context, kind: String) = send(context, Intent(context, IslandService::class.java).setAction(ACTION_PREVIEW).putExtra(EXTRA_PREVIEW, kind))

        fun relayout(context: Context) {
            if (running) send(context, Intent(context, IslandService::class.java).setAction(ACTION_RELAYOUT))
        }

        fun setCalibrating(context: Context, on: Boolean) {
            if (running) send(context, Intent(context, IslandService::class.java).setAction(ACTION_CALIBRATING).putExtra(EXTRA_ON, on))
        }

        fun resync(context: Context) {
            if (running) send(context, Intent(context, IslandService::class.java).setAction(ACTION_RESYNC))
        }

        private fun send(context: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
