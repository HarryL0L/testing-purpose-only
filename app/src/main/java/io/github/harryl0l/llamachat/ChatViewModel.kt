package io.github.harryl0l.llamachat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.harryl0l.llamachat.data.AppSettings
import io.github.harryl0l.llamachat.data.ModelPreset
import io.github.harryl0l.llamachat.data.ModelRepository
import io.github.harryl0l.llamachat.data.SettingsStore
import io.github.harryl0l.llamachat.engine.EngineState
import io.github.harryl0l.llamachat.engine.LlamaEngine
import io.github.harryl0l.llamachat.media.DecodedMedia
import io.github.harryl0l.llamachat.media.MediaDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class AttachmentKind { Image, Video }

data class Attachment(val uri: Uri, val kind: AttachmentKind)

enum class Role { User, Assistant }

class ChatMessage(
    val id: Long,
    val role: Role,
    text: String,
    val attachments: List<Attachment> = emptyList(),
) {
    var text by mutableStateOf(text)
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

    val downloads = mutableStateMapOf<String, DownloadState>()
    var importProgress by mutableStateOf<Float?>(null)
        private set
    var localModels by mutableStateOf(repo.localModels())
        private set

    private var genJob: Job? = null
    private var nextId = 0L

    init {
        viewModelScope.launch {
            val s = store.current()
            val model = s.modelPath?.let(::File)
            if (model != null && model.exists()) load(model, s.mmprojPath?.let(::File)?.takeIf { it.exists() })
        }
    }

    // ---------------------------------------------------------------- models

    fun refreshLocalModels() { localModels = repo.localModels() }

    fun load(model: File, mmproj: File?) {
        viewModelScope.launch {
            stop()
            genJob?.join()
            messages.clear()
            store.update { it.copy(modelPath = model.absolutePath, mmprojPath = mmproj?.absolutePath) }
            val s = store.current()
            engine.load(model, mmproj, s.contextSize, s.threads, s.sampling, s.systemPrompt)
            contextUsage = engine.contextUsage()
            (engine.state.value as? EngineState.Failed)?.let { snackbar = it.message }
        }
    }

    fun loadPreset(preset: ModelPreset) {
        val (m, p) = repo.presetFiles(preset)
        load(m, p)
    }

    fun download(preset: ModelPreset) {
        if (downloads[preset.id] is DownloadState.Running) return
        downloads[preset.id] = DownloadState.Running(0f)
        viewModelScope.launch {
            try {
                repo.download(preset) { p -> downloads[preset.id] = DownloadState.Running(p) }
                downloads.remove(preset.id)
                refreshLocalModels()
                loadPreset(preset)
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

    fun deleteModel(file: File) {
        viewModelScope.launch {
            val s = settings.value
            if (s.modelPath == file.absolutePath || s.mmprojPath == file.absolutePath) {
                stop(); genJob?.join()
                engine.unload()
                store.update { it.copy(modelPath = null, mmprojPath = null) }
            }
            repo.delete(file)
            refreshLocalModels()
        }
    }

    fun updateSettings(reloadNeeded: Boolean, transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            store.update(transform)
            val s = store.current()
            if (reloadNeeded) {
                val m = s.modelPath?.let(::File)
                if (m != null && m.exists() && engine.state.value !is EngineState.Loading) {
                    load(m, s.mmprojPath?.let(::File))
                }
            } else {
                engine.updateSampling(s.sampling, s.systemPrompt)
            }
        }
    }

    // ---------------------------------------------------------------- chat

    fun attach(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        for (uri in uris) {
            val type = resolver.getType(uri) ?: ""
            val kind = if (type.startsWith("video")) AttachmentKind.Video else AttachmentKind.Image
            pendingAttachments += Attachment(uri, kind)
        }
    }

    fun removeAttachment(a: Attachment) { pendingAttachments.remove(a) }

    fun newChat() {
        viewModelScope.launch {
            stop(); genJob?.join()
            messages.clear()
            pendingAttachments.clear()
            engine.resetChat()
            contextUsage = engine.contextUsage()
        }
    }

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
        messages += ChatMessage(nextId++, Role.User, text, attachments)
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
                            MediaDecoder.decodeImage(app, a.uri)
                        }
                        AttachmentKind.Video -> {
                            reply.status = "Sampling video frames…"
                            MediaDecoder.decodeVideo(app, a.uri, settings.value.maxVideoFrames, engine::videoConfig)
                                .also { v -> reply.status = "Watching ${v.timestamps.size} frames…" }
                        }
                    }
                }
                if (media.isEmpty()) reply.status = "Thinking…"
                val prompt = text.ifEmpty { if (media.any { it is DecodedMedia.Video }) "Describe this video." else "Describe this image." }

                var tokens = 0
                var start = 0L
                engine.generate(prompt, media).collect { piece ->
                    if (tokens == 0) start = System.nanoTime()
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
            }
        }
    }

    private fun tps(tokens: Int, start: Long): String {
        val s = (System.nanoTime() - start) / 1e9
        return if (s <= 0) "" else "%.1f tok/s".format(tokens / s)
    }
}
