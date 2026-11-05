package io.github.harryl0l.llamachat

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.harryl0l.llamachat.data.AppSettings
import io.github.harryl0l.llamachat.data.ChatStore
import io.github.harryl0l.llamachat.data.ChatSummary
import io.github.harryl0l.llamachat.data.LinkedFolder
import io.github.harryl0l.llamachat.data.LocalModel
import io.github.harryl0l.llamachat.data.ModelPreset
import io.github.harryl0l.llamachat.data.ModelRepository
import io.github.harryl0l.llamachat.data.SavedChat
import io.github.harryl0l.llamachat.data.SavedMedia
import io.github.harryl0l.llamachat.data.SavedMessage
import io.github.harryl0l.llamachat.data.SettingsStore
import io.github.harryl0l.llamachat.engine.EngineState
import io.github.harryl0l.llamachat.engine.LlamaEngine
import io.github.harryl0l.llamachat.engine.ModelSpec
import io.github.harryl0l.llamachat.engine.ProjectorSpec
import io.github.harryl0l.llamachat.engine.RestoredMessage
import io.github.harryl0l.llamachat.media.DecodedMedia
import io.github.harryl0l.llamachat.media.MediaDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AttachmentKind { Image, Video }

data class Attachment(val uri: Uri, val kind: AttachmentKind)

enum class Role { User, Assistant }

class ChatMessage(
    val id: Long,
    val role: Role,
    text: String,
    attachments: List<Attachment> = emptyList(),
    /** user: what the model saw (text or a default instruction when only media was sent) */
    var prompt: String = text,
    /** user: media as saved with the chat (restorable) */
    var savedMedia: List<SavedMedia> = emptyList(),
) {
    var text by mutableStateOf(text)          // assistant: raw reply, "<think>…</think>" included
    var attachments by mutableStateOf(attachments)
    var status by mutableStateOf<String?>(null)   // "Reading video…", tokens/s, …
    var streaming by mutableStateOf(false)
    var error by mutableStateOf(false)
}

sealed interface DownloadState {
    data class Running(val progress: Float) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val engine = LlamaEngine(app)
    val repo = ModelRepository(app)
    private val store = SettingsStore(app)
    private val chatStore = ChatStore(app)

    val settings: StateFlow<AppSettings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val engineState: StateFlow<EngineState> = engine.state

    val messages = mutableStateListOf<ChatMessage>()
    val pendingAttachments = mutableStateListOf<Attachment>()
    var input by mutableStateOf("")
    var generating by mutableStateOf(false)
        private set
    var contextUsage by mutableStateOf(0 to 0)
        private set
    var snackbar by mutableStateOf<String?>(null)

    var chats by mutableStateOf<List<ChatSummary>>(emptyList())
        private set
    var currentChatId by mutableStateOf<String?>(null)
        private set

    val downloads = mutableStateMapOf<String, DownloadState>()
    var importProgress by mutableStateOf<Float?>(null)
        private set
    var localModels by mutableStateOf<List<LocalModel>>(emptyList())
        private set
    var linkedFolders by mutableStateOf<List<LinkedFolder>>(emptyList())
        private set

    private var genJob: Job? = null
    private var nextId = 0L

    /** Name of the last used model (offered as a one-tap "Load" when nothing is loaded). */
    var lastModelName by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            refreshChats()
            refreshLocalModels()
            val s = store.current()
            refreshLastModel()
            // only load on start when the user opted in; otherwise wait for "Load"
            if (s.autoLoad) loadLast()
        }
    }

    private suspend fun refreshLastModel() {
        val ref = store.current().modelPath
        lastModelName = withContext(Dispatchers.IO) {
            ref?.takeIf { repo.exists(it) }?.let { repo.displayName(it).removeSuffix(".gguf") }
        }
    }

    /** Loads the last used model (and its projector). */
    fun loadLast() {
        viewModelScope.launch {
            val s = store.current()
            val model = s.modelPath
            val ok = model != null && withContext(Dispatchers.IO) { repo.exists(model) }
            if (!ok) { snackbar = "The last model file is gone. Choose a model."; refreshLastModel(); return@launch }
            val mmproj = s.mmprojPath?.takeIf { withContext(Dispatchers.IO) { repo.exists(it) } }
            load(model!!, mmproj)
        }
    }

    /** Frees the model from memory. The chat stays and is restored when a model is loaded again. */
    fun unload() {
        viewModelScope.launch {
            stop(); genJob?.join()
            engine.unload()
            contextUsage = 0 to 0
            refreshLastModel()
        }
    }

    // ---------------------------------------------------------------- models

    fun refreshLocalModels() {
        viewModelScope.launch {
            val folders = store.current().linkedFolders
            val (models, infos) = withContext(Dispatchers.IO) {
                repo.localModels(folders) to folders.map { repo.folderInfo(it) }
            }
            localModels = models
            linkedFolders = infos
        }
    }

    /** [model] / [mmproj] are absolute paths or content:// URIs (linked folders, used in place). */
    fun load(model: String, mmproj: String?) {
        viewModelScope.launch {
            stop()
            genJob?.join()
            store.update { it.copy(modelPath = model, mmprojPath = mmproj) }
            val s = store.current()
            val resolver = getApplication<Application>().contentResolver
            val open = mutableListOf<ParcelFileDescriptor>()
            try {
                val spec = withContext(Dispatchers.IO) {
                    val name = repo.displayName(model).removeSuffix(".gguf")
                    val projector = mmproj?.let { ref ->
                        if (ref.startsWith("content://")) {
                            val pfd = resolver.openFileDescriptor(Uri.parse(ref), "r")!!.also { open += it }
                            // try in place first; copy just the projector if the path can't be opened
                            ProjectorSpec("/proc/self/fd/${pfd.fd}") { repo.cachedProjector(ref) }
                        } else ProjectorSpec(ref)
                    }
                    if (model.startsWith("content://")) {
                        val pfd = resolver.openFileDescriptor(Uri.parse(model), "r")!!.also { open += it }
                        ModelSpec(name, null, pfd.fd, projector)
                    } else ModelSpec(name, model, -1, projector)
                }
                engine.load(spec, s.contextSize, s.threads, s.sampling, s.systemPrompt, s.thinking, s.repack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar = e.message ?: "Could not open the model file"
            } finally {
                open.forEach { runCatching { it.close() } } // native code keeps its own descriptor
            }
            (engine.state.value as? EngineState.Failed)?.let { snackbar = it.message }
            if (engine.state.value is EngineState.Ready && messages.isNotEmpty()) restoreIntoEngine()
            contextUsage = engine.contextUsage()
            refreshLastModel()
        }
    }

    fun loadPreset(preset: ModelPreset) {
        val (m, p) = repo.presetFiles(preset)
        load(m.absolutePath, p.absolutePath)
    }

    fun download(preset: ModelPreset) {
        if (downloads[preset.id] is DownloadState.Running) return
        downloads[preset.id] = DownloadState.Running(0f)
        viewModelScope.launch {
            try {
                repo.download(preset) { p -> downloads[preset.id] = DownloadState.Running(p) }
                downloads.remove(preset.id)
                refreshLocalModels()
                snackbar = "${preset.title} downloaded. Tap \"Use this model\" to load it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloads[preset.id] = DownloadState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            importProgress = 0f
            try {
                repo.import(uri) { importProgress = it }
                refreshLocalModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar = e.message ?: "Import failed"
            } finally {
                importProgress = null
            }
        }
    }

    fun linkFolder(uri: Uri) {
        viewModelScope.launch {
            try {
                repo.linkFolder(uri)
                store.update { it.copy(linkedFolders = it.linkedFolders + uri.toString()) }
                refreshLocalModels()
            } catch (e: Exception) {
                snackbar = e.message ?: "Could not link that folder"
            }
        }
    }

    fun unlinkFolder(uri: String) {
        viewModelScope.launch {
            val s = store.current()
            val usesIt = localModels.any { m -> m.folderUri == uri && (m.ref == s.modelPath || m.ref == s.mmprojPath) }
            if (usesIt) { stop(); genJob?.join(); engine.unload() }
            repo.unlinkFolder(uri)
            store.update {
                it.copy(
                    linkedFolders = it.linkedFolders - uri,
                    modelPath = if (usesIt) null else it.modelPath,
                    mmprojPath = if (usesIt) null else it.mmprojPath,
                )
            }
            refreshLocalModels()
        }
    }

    /** Deletes a model from app storage (files in linked folders are never deleted). */
    fun deleteModel(model: LocalModel) {
        if (model.inLinkedFolder) return
        viewModelScope.launch {
            val s = settings.value
            if (s.modelPath == model.ref || s.mmprojPath == model.ref) {
                stop(); genJob?.join()
                engine.unload()
                store.update { it.copy(modelPath = null, mmprojPath = null) }
            }
            repo.delete(File(model.ref))
            refreshLocalModels()
        }
    }

    fun updateSettings(reloadNeeded: Boolean, transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            store.update(transform)
            val s = store.current()
            if (reloadNeeded) {
                // apply to the model that is already loaded; never load one on our own
                val m = s.modelPath
                if (m != null && engine.state.value is EngineState.Ready) load(m, s.mmprojPath)
            } else {
                engine.updateSampling(s.sampling, s.systemPrompt)
            }
        }
    }

    fun setThinking(enabled: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(thinking = enabled) }
            engine.setThinking(enabled)
        }
    }

    // ---------------------------------------------------------------- chats

    private suspend fun refreshChats() { chats = chatStore.list() }

    fun newChat() {
        viewModelScope.launch {
            stop(); genJob?.join()
            messages.clear()
            pendingAttachments.clear()
            currentChatId = null
            engine.resetChat()
            contextUsage = engine.contextUsage()
        }
    }

    fun openChat(id: String) {
        if (id == currentChatId) return
        viewModelScope.launch {
            stop(); genJob?.join()
            val chat = chatStore.load(id)
            if (chat == null) { snackbar = "Could not open that chat"; return@launch }
            messages.clear()
            pendingAttachments.clear()
            currentChatId = chat.id
            val dir = chatStore.dir(chat.id)
            chat.messages.forEach { m ->
                val role = if (m.role == "user") Role.User else Role.Assistant
                messages += ChatMessage(
                    id = nextId++,
                    role = role,
                    text = m.text,
                    attachments = m.media.map { it.toAttachment(dir) },
                    prompt = m.prompt,
                    savedMedia = m.media,
                )
            }
            if (engine.state.value is EngineState.Ready) restoreIntoEngine()
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            if (id == currentChatId) newChat()
            chatStore.delete(id)
            refreshChats()
        }
    }

    /** Puts the visible conversation into the engine; it is evaluated with the next message. */
    private suspend fun restoreIntoEngine() {
        val id = currentChatId ?: return
        val restored = messages.filter { !it.error && (it.role == Role.User || it.text.isNotEmpty()) }.map { m ->
            if (m.role == Role.User) {
                val media = m.savedMedia.mapNotNull { runCatching { chatStore.loadMedia(id, it) }.getOrNull() }
                RestoredMessage("user", m.prompt, media)
            } else RestoredMessage("assistant", m.text)
        }
        engine.restore(restored)
        contextUsage = engine.contextUsage()
    }

    private suspend fun persistChat() {
        val id = currentChatId ?: return
        val firstUser = messages.firstOrNull { it.role == Role.User }
        val title = firstUser?.text?.lineSequence()?.firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
            ?: when (firstUser?.attachments?.firstOrNull()?.kind) {
                AttachmentKind.Video -> "Video chat"
                AttachmentKind.Image -> "Image chat"
                null -> "New chat"
            }
        chatStore.save(
            SavedChat(
                id = id,
                title = title,
                updated = System.currentTimeMillis(),
                messages = messages.filter { !it.error && !it.streaming }.map { m ->
                    SavedMessage(
                        role = if (m.role == Role.User) "user" else "assistant",
                        text = m.text,
                        prompt = m.prompt,
                        media = m.savedMedia,
                    )
                },
            )
        )
        refreshChats()
    }

    private fun SavedMedia.toAttachment(dir: File) = Attachment(
        Uri.fromFile(File(dir, thumbnail)),
        if (this is SavedMedia.Video) AttachmentKind.Video else AttachmentKind.Image,
    )

    // ---------------------------------------------------------------- sending

    fun attach(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            val type = resolver.getType(uri) ?: ""
            val kind = if (type.startsWith("video")) AttachmentKind.Video else AttachmentKind.Image
            pendingAttachments += Attachment(uri, kind)
        }
    }

    fun removeAttachment(a: Attachment) { pendingAttachments.remove(a) }

    fun stop() { genJob?.cancel() }

    fun send() {
        val text = input.trim()
        val attachments = pendingAttachments.toList()
        if ((text.isEmpty() && attachments.isEmpty()) || generating) return
        val ready = engine.state.value as? EngineState.Ready
        if (ready == null) { snackbar = "Load a model first"; return }
        if (attachments.isNotEmpty() && !ready.info.vision) {
            snackbar = "This model has no vision projector (mmproj) loaded"
            return
        }

        input = ""
        pendingAttachments.clear()
        val chatId = currentChatId ?: System.currentTimeMillis().toString().also { currentChatId = it }
        val user = ChatMessage(nextId++, Role.User, text, attachments)
        messages += user
        val reply = ChatMessage(nextId++, Role.Assistant, "").apply { streaming = true }
        messages += reply
        generating = true

        genJob = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val media = attachments.map { a ->
                    when (a.kind) {
                        AttachmentKind.Image -> {
                            reply.status = "Looking at the image…"
                            MediaDecoder.decodeImage(
                                app, a.uri,
                                MediaDecoder.IMAGE_QUALITY_PIXELS[settings.value.imageQuality.coerceIn(0, 2)],
                            )
                        }
                        AttachmentKind.Video -> {
                            reply.status = "Sampling video frames…"
                            val s = settings.value
                            MediaDecoder.decodeVideo(
                                app, a.uri, s.maxVideoFrames,
                                MediaDecoder.VIDEO_QUALITY_PIXELS[s.videoQuality.coerceIn(0, 2)],
                                engine::videoConfig,
                            )
                                .also { v -> reply.status = "Watching ${v.timestamps.size} frames…" }
                        }
                    }
                }
                // keep a copy with the chat so it can be reopened later
                val saved = media.map { chatStore.saveMedia(chatId, it) }
                user.savedMedia = saved
                user.attachments = saved.map { it.toAttachment(chatStore.dir(chatId)) }
                user.prompt = text.ifEmpty {
                    if (media.any { it is DecodedMedia.Video }) "Describe this video." else "Describe this image."
                }
                persistChat()

                if (media.isEmpty()) reply.status = "Thinking…"
                // show how far prompt processing (vision encoder + prefill) has got
                val what = when {
                    media.any { it is DecodedMedia.Video } -> "Processing video"
                    media.isNotEmpty() -> "Processing image"
                    else -> "Reading"
                }
                val progressJob = launch {
                    while (true) {
                        kotlinx.coroutines.delay(400)
                        val p = engine.promptProgress()
                        if (p >= 0f) reply.status = "$what… ${(p * 100).toInt()}%"
                    }
                }
                var tokens = 0
                var start = 0L
                engine.generate(user.prompt, media).onCompletion { progressJob.cancel() }.collect { piece ->
                    if (tokens == 0) { start = System.nanoTime(); progressJob.cancel() }
                    tokens++
                    reply.text += piece
                    if (tokens % 8 == 0) reply.status = tps(tokens, start)
                }
                reply.status = if (tokens > 1) tps(tokens, start) else null
            } catch (e: CancellationException) {
                reply.status = "Stopped"
            } catch (e: Exception) {
                reply.error = true
                reply.status = null
                if (reply.text.isEmpty()) reply.text = e.message ?: "Something went wrong"
            } finally {
                reply.streaming = false
                generating = false
                contextUsage = engine.contextUsage()
                withContext(NonCancellable) { runCatching { persistChat() } }
            }
        }
    }

    private fun tps(tokens: Int, start: Long): String {
        val s = (System.nanoTime() - start) / 1e9
        return if (s <= 0) "" else "%.1f tok/s".format(tokens / s)
    }
}
