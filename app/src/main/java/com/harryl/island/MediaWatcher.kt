package com.harryl.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette

/**
 * Tracks the most relevant media session (requires notification-listener access) and turns it
 * into [IslandContent.Music]. Album art is downscaled once per track and its accent colour is
 * extracted off the main thread.
 */
class MediaWatcher(
    private val context: Context,
    private val onChange: (IslandContent.Music?) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, IslandNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val defaultAccent = ContextCompat.getColor(context, R.color.island_accent)

    private var started = false
    private var controller: MediaController? = null
    private var last: IslandContent.Music? = null

    private var artKey: String? = null
    private var art: Bitmap? = null
    private var accent: Int = defaultAccent

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            refresh()
        } catch (_: SecurityException) {
            // Notification access not granted yet; IslandNotificationListener.onListenerConnected retries.
        }
    }

    fun stop() {
        if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        setController(null)
    }

    fun refresh() {
        try {
            pick(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            setController(null)
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    private fun pick(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        val current = controller
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val best = playing
            ?: sessions.firstOrNull { current != null && it.sessionToken == current.sessionToken && it.metadata != null }
            ?: sessions.firstOrNull { it.metadata != null && isActiveState(it.playbackState?.state) }
        setController(best)
    }

    private fun isActiveState(state: Int?) = when (state) {
        null, PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
        else -> true
    }

    private fun setController(c: MediaController?) {
        if (controller != null && c != null && controller!!.sessionToken == c.sessionToken) {
            publish()
            return
        }
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, handler)
        publish()
    }

    private fun publish() {
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState
        if (c == null || md == null || !isActiveState(ps?.state)) {
            emit(null)
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(c.packageName)
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val key = "${c.packageName}|$title|$artist"
        if (key != artKey || art == null) {
            artKey = key
            updateArt(md, key)
        }
        emit(
            IslandContent.Music(
                packageName = c.packageName,
                title = title,
                artist = artist,
                art = art,
                playing = ps?.state == PlaybackState.STATE_PLAYING,
                position = ps?.position ?: 0L,
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                accent = accent,
            ),
        )
    }

    private fun emit(m: IslandContent.Music?) {
        if (m == last) return
        last = m
        onChange(m)
    }

    private fun updateArt(md: MediaMetadata, key: String) {
        val src = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (src == null || src.isRecycled || src.width <= 0 || src.height <= 0) {
            art = null
            accent = defaultAccent
            return
        }
        val target = ART_PX
        val scale = target.toFloat() / maxOf(src.width, src.height)
        art = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true)
        } else src
        accent = defaultAccent
        val bitmap = art ?: return
        Palette.from(bitmap).maximumColorCount(12).generate { palette ->
            if (artKey != key || palette == null) return@generate
            val c = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(defaultAccent)))
            if (c != accent) {
                accent = c
                publish()
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val ART_PX = 160
    }
}
