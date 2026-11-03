package com.harryl.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.SystemClock

enum class DeviceKind { EARBUDS, HEADPHONES, WIRED, WATCH, SPEAKER, OTHER }

enum class LiveKind { CALL, STOPWATCH, TIMER, NAVIGATION, PROGRESS, OTHER }

/** Everything the island can display. */
sealed class IslandContent {

    /** Stable key used to remember which item the user expanded. */
    abstract val expandKey: String

    data class Music(
        val packageName: String,
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        /** SystemClock.elapsedRealtime() at which [position] was sampled. */
        val positionAt: Long,
        val speed: Float,
        val accent: Int,
    ) : IslandContent() {
        val trackKey: String get() = "$packageName|$title|$artist"
        override val expandKey: String get() = "music:$packageName"

        fun positionNow(): Long {
            if (!playing) return position
            val elapsed = SystemClock.elapsedRealtime() - positionAt
            return (position + elapsed * speed).toLong().coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        }
    }

    /** Ongoing notification shown as a persistent pill: stopwatch, timer, call, download, navigation. */
    data class Live(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val kind: LiveKind,
        /** Wall-clock base of a chronometer (Notification.when), or 0 when the app shows no running clock. */
        val chronoBase: Long,
        val countDown: Boolean,
        /** 0..100, or -1 without determinate progress. */
        val progress: Int,
        val accent: Int,
        val contentIntent: PendingIntent?,
    ) : IslandContent() {
        override val expandKey: String get() = "live:$key"

        fun chronoText(now: Long = System.currentTimeMillis()): String? {
            if (chronoBase <= 0L) return null
            return if (countDown) Clock.format(chronoBase - now + 999) else Clock.format(now - chronoBase)
        }
    }

    data class Notice(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val contentIntent: PendingIntent?,
    ) : IslandContent() {
        override val expandKey: String get() = "notice:$key"
    }

    data class Device(
        val address: String,
        val kind: DeviceKind,
        val name: String,
        /** 0..100, or -1 when the device does not report battery. */
        val battery: Int,
        val connected: Boolean,
    ) : IslandContent() {
        override val expandKey: String get() = "device:$address"
    }

    data class Power(val level: Int, val charging: Boolean) : IslandContent() {
        override val expandKey: String get() = "power"
    }
}

sealed class IslandEvent {
    data class Music(val music: IslandContent.Music?) : IslandEvent()
    data class Transient(val content: IslandContent) : IslandEvent()
    data class DismissNotice(val key: String) : IslandEvent()
    data class Live(val live: IslandContent.Live) : IslandEvent()
    data class LiveRemoved(val key: String) : IslandEvent()
    /** A device's real connection state. [announce] is false for the initial scan. */
    data class DeviceState(val device: IslandContent.Device, val announce: Boolean) : IslandEvent()
    data class DeviceBattery(val address: String, val battery: Int) : IslandEvent()
    object BluetoothOff : IslandEvent()
    data class Screen(val on: Boolean) : IslandEvent()
    /** Notification shade or lock screen covering the screen (from the accessibility service). */
    data class Shade(val open: Boolean) : IslandEvent()
    /** The accessibility service connected or went away, so the overlay window type changes. */
    object HostChanged : IslandEvent()
    object ListenerConnected : IslandEvent()
}

/**
 * Tiny in-process bus between the notification listener / receivers and the overlay service.
 * Events posted before the service is up are queued (bounded) and replayed when it attaches.
 */
object IslandBus {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var sink: ((IslandEvent) -> Unit)? = null
    private val pending = ArrayDeque<IslandEvent>()

    fun post(event: IslandEvent) {
        main.post {
            val s = sink
            if (s != null) s(event) else {
                pending.addLast(event)
                while (pending.size > 16) pending.removeFirst()
            }
        }
    }

    fun attach(s: (IslandEvent) -> Unit) {
        main.post {
            sink = s
            while (pending.isNotEmpty()) s(pending.removeFirst())
        }
    }

    fun detach() {
        main.post { sink = null }
    }
}
