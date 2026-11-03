package com.harryl.island

import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.SystemClock

enum class DeviceKind { EARBUDS, HEADPHONES, WIRED, WATCH, SPEAKER, OTHER }

/** Everything the island can display. */
sealed class IslandContent {

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

        fun positionNow(): Long {
            if (!playing) return position
            val elapsed = SystemClock.elapsedRealtime() - positionAt
            return (position + elapsed * speed).toLong().coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
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
    ) : IslandContent()

    data class Device(
        val address: String,
        val kind: DeviceKind,
        val name: String,
        /** 0..100, or -1 when the device does not report battery. */
        val battery: Int,
        val connected: Boolean,
    ) : IslandContent()

    data class Power(val level: Int, val charging: Boolean) : IslandContent()
}

sealed class IslandEvent {
    data class Music(val music: IslandContent.Music?) : IslandEvent()
    data class Transient(val content: IslandContent) : IslandEvent()
    data class DismissNotice(val key: String) : IslandEvent()
    data class DeviceBattery(val address: String, val battery: Int) : IslandEvent()
    data class Screen(val on: Boolean) : IslandEvent()
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
                while (pending.size > 8) pending.removeFirst()
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
