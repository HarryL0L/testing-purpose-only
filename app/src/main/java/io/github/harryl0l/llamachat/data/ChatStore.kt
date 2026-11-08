package io.github.harryl0l.llamachat.data

import android.content.Context
import io.github.harryl0l.llamachat.media.DecodedMedia
import io.github.harryl0l.llamachat.media.MediaDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Media saved with a chat, as JPEGs in the chat's folder (paths are relative to it). */
sealed interface SavedMedia {
    data class Image(val file: String) : SavedMedia
    class Video(val frames: List<String>, val timestamps: FloatArray, val template: Int) : SavedMedia

    val thumbnail: String get() = when (this) {
        is Image -> file
        is Video -> frames.first()
    }
}

/**
 * One message. For user messages [prompt] is what the model saw (may differ from [text] when the
 * user only sent media); for assistant messages [text] is the raw reply, reasoning included.
 */
class SavedMessage(
    val role: String,
    val text: String,
    val prompt: String,
    val media: List<SavedMedia>,
    /** "image" for an image-creation prompt and its result (never given to the chat model) */
    val kind: String = "",
)

class SavedChat(val id: String, val title: String, val updated: Long, val messages: List<SavedMessage>)

data class ChatSummary(val id: String, val title: String, val updated: Long)

/** Chats are stored as files/chats/<id>/chat.json plus the JPEGs of their media. */
class ChatStore(context: Context) {
    private val root = File(context.filesDir, "chats").apply { mkdirs() }

    fun dir(id: String) = File(root, id)

    suspend fun list(): List<ChatSummary> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().mapNotNull { d ->
            runCatching {
                val o = JSONObject(File(d, "chat.json").readText())
                ChatSummary(o.getString("id"), o.getString("title"), o.getLong("updated"))
            }.getOrNull()
        }.sortedByDescending { it.updated }
    }

    suspend fun load(id: String): SavedChat? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(File(dir(id), "chat.json").readText())
            val msgs = o.getJSONArray("messages")
            SavedChat(
                id = o.getString("id"),
                title = o.getString("title"),
                updated = o.getLong("updated"),
                messages = List(msgs.length()) { i ->
                    val m = msgs.getJSONObject(i)
                    val media = m.optJSONArray("media") ?: JSONArray()
                    SavedMessage(
                        role = m.getString("role"),
                        text = m.getString("text"),
                        prompt = m.optString("prompt", m.getString("text")),
                        media = List(media.length()) { j -> media.getJSONObject(j).toMedia() },
                        kind = m.optString("kind", ""),
                    )
                },
            )
        }.getOrNull()
    }

    suspend fun save(chat: SavedChat) = withContext(Dispatchers.IO) {
        val o = JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("updated", chat.updated)
            .put("messages", JSONArray().apply {
                chat.messages.forEach { m ->
                    put(JSONObject()
                        .put("role", m.role)
                        .put("text", m.text)
                        .put("prompt", m.prompt)
                        .put("kind", m.kind)
                        .put("media", JSONArray().apply { m.media.forEach { put(it.toJson()) } }))
                }
            })
        val d = dir(chat.id).apply { mkdirs() }
        val tmp = File(d, "chat.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(d, "chat.json"))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { dir(id).deleteRecursively() }

    /** Writes decoded media into the chat folder so the chat can be reopened later. */
    suspend fun saveMedia(chatId: String, media: DecodedMedia): SavedMedia = withContext(Dispatchers.IO) {
        val d = dir(chatId).apply { mkdirs() }
        val stamp = System.nanoTime()
        when (media) {
            is DecodedMedia.Image -> {
                val name = "img_$stamp.jpg"
                MediaDecoder.saveJpeg(media.rgb, 0, media.width, media.height, File(d, name))
                SavedMedia.Image(name)
            }
            is DecodedMedia.Video -> {
                val frameBytes = media.width * media.height * 3
                val names = media.timestamps.indices.map { i ->
                    "vid_${stamp}_$i.jpg".also {
                        MediaDecoder.saveJpeg(media.frames, i * frameBytes, media.width, media.height, File(d, it))
                    }
                }
                SavedMedia.Video(names, media.timestamps, media.template)
            }
        }
    }

    suspend fun loadMedia(chatId: String, media: SavedMedia): DecodedMedia = withContext(Dispatchers.IO) {
        val d = dir(chatId)
        when (media) {
            is SavedMedia.Image -> {
                val (rgb, w, h) = MediaDecoder.loadJpeg(File(d, media.file))
                DecodedMedia.Image(rgb, w, h)
            }
            is SavedMedia.Video -> {
                val frames = media.frames.map { MediaDecoder.loadJpeg(File(d, it)) }
                val (_, w, h) = frames.first()
                val out = ByteArray(w * h * 3 * frames.size)
                frames.forEachIndexed { i, (rgb, _, _) -> System.arraycopy(rgb, 0, out, i * w * h * 3, rgb.size) }
                DecodedMedia.Video(out, w, h, media.timestamps, media.template)
            }
        }
    }

    private fun SavedMedia.toJson(): JSONObject = when (this) {
        is SavedMedia.Image -> JSONObject().put("kind", "image").put("file", file)
        is SavedMedia.Video -> JSONObject()
            .put("kind", "video")
            .put("frames", JSONArray(frames))
            .put("timestamps", JSONArray().apply { timestamps.forEach { put(it.toDouble()) } })
            .put("template", template)
    }

    private fun JSONObject.toMedia(): SavedMedia = when (getString("kind")) {
        "video" -> {
            val f = getJSONArray("frames")
            val t = getJSONArray("timestamps")
            SavedMedia.Video(
                List(f.length()) { f.getString(it) },
                FloatArray(t.length()) { t.getDouble(it).toFloat() },
                getInt("template"),
            )
        }
        else -> SavedMedia.Image(getString("file"))
    }
}
