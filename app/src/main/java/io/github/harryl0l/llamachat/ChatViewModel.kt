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
import io.github.harryl0l.llamachat.data.effectiveThreads
import io.github.harryl0l.llamachat.data.ChatStore
import io.github.harryl0l.llamachat.data.ChatSummary
import io.github.harryl0l.llamachat.data.CpuInfo
import io.github.harryl0l.llamachat.data.GpuDriver
import io.github.harryl0l.llamachat.data.GpuDriverStore
import io.github.harryl0l.llamachat.data.LinkedFolder
import io.github.harryl0l.llamachat.data.LocalModel
import io.github.harryl0l.llamachat.data.ModelPreset
import io.github.harryl0l.llamachat.data.ModelRepository
import io.github.harryl0l.llamachat.data.SavedChat
import io.github.harryl0l.llamachat.data.SavedMedia
import io.github.harryl0l.llamachat.data.SavedMessage
import io.github.harryl0l.llamachat.data.SettingsStore
import io.github.harryl0l.llamachat.engine.EngineState
import io.github.harryl0l.llamachat.engine.ImageEngine
import io.github.harryl0l.llamachat.data.IMAGE_PRESETS
import io.github.harryl0l.llamachat.data.ImageModelPreset
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

/** An image model "Create image" can use: a downloaded preset or an imported model. */
class ImageModelChoice(
    val id: String,
    val title: String,
    val model: File,
    /** LLM text encoder (diffusion-only models such as Anima), else null */
    val llm: File?,
    val taesd: File?,
    val defaultSteps: Int,
    val maxSteps: Int,
    val defaultCfg: Float,
    val defaultSampler: Int,
    val custom: Boolean,
    val scheduler: Int = -1,
    val sizes: List<Int> = listOf(256, 384, 512),
    val defaultSize: Int = 512,
) {
    /** the size to use: the chosen one if this model offers it, else the model's default */
    fun sizeFor(chosen: Int): Int = if (chosen in sizes) chosen else defaultSize
}

class ChatMessage(
    val id: Long,
    val role: Role,
    text: String,
    attachments: List<Attachment> = emptyList(),
    /** user: what the model saw (text or a default instruction when only media was sent) */
    var prompt: String = text,
    /** user: media as saved with the chat (restorable) */
    var savedMedia: List<SavedMedia> = emptyList(),
    /** part of image creation (prompt or result): shown in the chat, never given to the chat model */
    val imageRequest: Boolean = false,
) {
    /** 0..1 while an image is being created */
    var progress by mutableStateOf<Float?>(null)
    var text by mutableStateOf(text)          // assistant: raw reply, "<think>…</think>" included
    var attachments by mutableStateOf(attachments)
    var status by mutableStateOf<String?>(null)   // "Reading video…", tokens/s, …
    var streaming by mutableStateOf(false)
    var error by mutableStateOf(false)
}

sealed interface DownloadState {
    /** [verifying]: the download finished and its checksum is being checked */
    data class Running(val progress: Float, val verifying: Boolean = false, val converting: Boolean = false) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val engine = LlamaEngine(app)
    val imageEngine = ImageEngine()
    val repo = ModelRepository(app)
    private val store = SettingsStore(app)
    private val chatStore = ChatStore(app)
    private val driverStore = GpuDriverStore(app)

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

    /** see checkGpuCrash() */
    private val gpuMarker = File(app.noBackupFilesDir, "gpu_in_use")

    /** What Android recorded about the crash, shown in the GPU card (copyable) */
    var gpuCrashReport by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            refreshChats()
            refreshLocalModels()
            checkGpuCrash() // before any auto-load, so a GPU mode that crashed isn't tried again
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
            unloadModel()
            refreshLastModel()
        }
    }

    private suspend fun unloadModel() {
        stop(); genJob?.join()
        engine.unload()
        markGpuInUse(io.github.harryl0l.llamachat.engine.GPU_CPU)
        imageEngine.unload()
        contextUsage = 0 to 0
    }

    // ---------------------------------------------------------------- GPU crash guard

    /**
     * A GPU driver can crash the whole process (native code; it can't be caught). While a model
     * runs in a GPU mode a marker file exists; if the app's last exit was a crash and the marker
     * is still there, the next start switches back to CPU instead of crashing again.
     */

    private fun markGpuInUse(mode: Int) {
        runCatching {
            if (mode == io.github.harryl0l.llamachat.engine.GPU_CPU) gpuMarker.delete()
            else gpuMarker.writeText(if (mode == io.github.harryl0l.llamachat.engine.GPU_VULKAN) "Vulkan" else "Mali (OpenCL)")
        }
    }

    private suspend fun checkGpuCrash() {
        if (!BuildConfig.GPU_EXPERIMENTAL || !gpuMarker.exists()) return
        val mode = runCatching { gpuMarker.readText() }.getOrDefault("the GPU")
        gpuMarker.delete()
        val app = getApplication<Application>()
        val last = runCatching {
            app.getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        }.getOrNull() ?: return
        val reasons = mapOf(
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE to "native crash",
            android.app.ApplicationExitInfo.REASON_CRASH to "crash",
            android.app.ApplicationExitInfo.REASON_ANR to "not responding",
        )
        val reason = reasons[last.reason] ?: return // closed normally: nothing to do
        store.update { it.copy(gpuMode = io.github.harryl0l.llamachat.engine.GPU_CPU) }
        gpuCrashReport = withContext(Dispatchers.IO) {
            buildString {
                append("Last exit: $reason while running on $mode")
                last.description?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                append("\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                // the native crash report (tombstone) is binary; keep its readable lines
                val trace = runCatching { last.traceInputStream?.use { it.readBytes() } }.getOrNull()
                if (trace != null) {
                    val lines = String(trace, Charsets.ISO_8859_1).split(Regex("[^\\x20-\\x7e]+"))
                        .filter { l ->
                            val x = l.lowercase()
                            l.length >= 6 && (".so" in x || ".cpp:" in x || "signal" in x || "abort" in x || "fault" in x ||
                                "assert" in x || "error" in x || "ggml" in x || "vk_" in x || "vulkan" in x || "failed" in x)
                        }
                        .distinct().take(40)
                    if (lines.isNotEmpty()) append("\n").append(lines.joinToString("\n"))
                }
            }
        }
        snackbar = "The app crashed while using $mode, so it's back on CPU. Details are in Models → GPU."
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
                prepareGpuDriver()
                markGpuInUse(if (BuildConfig.GPU_EXPERIMENTAL) s.gpuMode else io.github.harryl0l.llamachat.engine.GPU_CPU)
                engine.load(
                    spec, s.contextSize, s.effectiveThreads, s.sampling, s.systemPrompt, s.thinking, s.repack,
                    gpuMode = if (BuildConfig.GPU_EXPERIMENTAL) s.gpuMode else io.github.harryl0l.llamachat.engine.GPU_CPU,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar = e.message ?: "Could not open the model file"
            } finally {
                open.forEach { runCatching { it.close() } } // native code keeps its own descriptor
            }
            (engine.state.value as? EngineState.Failed)?.let { snackbar = it.message }
            if (engine.state.value !is EngineState.Ready) markGpuInUse(io.github.harryl0l.llamachat.engine.GPU_CPU)
            // remember the model only once it loaded, so a model that fails (or gets the app killed
            // while loading) is not offered again / auto-loaded on every start
            if (engine.state.value is EngineState.Ready) store.update { it.copy(modelPath = model, mmprojPath = mmproj) }
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
                repo.download(preset) { p, verifying -> downloads[preset.id] = DownloadState.Running(p, verifying) }
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

    // ---------------------------------------------------------------- image creation

    /** composer toggle: the next message creates an image instead of chatting */
    var imageMode by mutableStateOf(false)
    val imageCreationSupported: Boolean get() = imageEngine.available

    fun selectImageModel(preset: ImageModelPreset) = selectImageModel(preset.id)

    /** Selecting another model resets steps / CFG / sampler to that model's defaults. */
    fun selectImageModel(id: String) {
        viewModelScope.launch { store.update { it.copy(imageModel = id, imageSteps = 0, imageCfg = 0f, imageSampler = -1) } }
    }

    /** Downloaded presets and imported models, in the order shown. */
    fun imageModelChoices(): List<ImageModelChoice> {
        return IMAGE_PRESETS.filter { repo.isDownloaded(it) }.map { p ->
            val (m, t) = repo.imageFiles(p)
            ImageModelChoice(
                p.id, p.title, m, repo.imageLlmFile(p), t, p.defaultSteps, p.maxSteps, p.cfgScale, p.sampler, custom = false,
                scheduler = p.scheduler, sizes = p.sizes, defaultSize = p.defaultSize,
            )
        } + repo.customImageModels().map { c ->
            ImageModelChoice(
                c.id, c.title, c.file, null, repo.taesdFile(xl = c.isXl),
                c.defaultSteps, c.maxSteps, c.defaultCfg, c.defaultSampler, custom = true,
            )
        }
    }

    fun deleteCustomImageModel(choice: ImageModelChoice) {
        if (!choice.custom) return
        viewModelScope.launch {
            stop(); genJob?.join()
            imageEngine.unload()
            choice.model.delete()
            if (store.current().imageModel == choice.id) store.update { it.copy(imageModel = null) }
            imageFilesVersion++
        }
    }

    fun downloadImageModel(preset: ImageModelPreset) {
        val key = "img:" + preset.id
        if (downloads[key] is DownloadState.Running) return
        downloads[key] = DownloadState.Running(0f)
        viewModelScope.launch {
            try {
                repo.download(
                    preset,
                    convert = { input, output, type ->
                        stop(); genJob?.join() // conversion runs on the image engine's thread
                        imageEngine.convert(input, output, type, settings.value.effectiveThreads)
                    },
                    onConverting = { downloads[key] = DownloadState.Running(1f, converting = true) },
                ) { p, verifying -> downloads[key] = DownloadState.Running(p, verifying) }
                downloads.remove(key)
                imageFilesVersion++
                if (store.current().imageModel == null) selectImageModel(preset)
                snackbar = "${preset.title} downloaded. Turn on \"Create image\" in the chat to use it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                downloads[key] = DownloadState.Failed(e.message ?: "Download failed")
            }
        }
    }

    fun deleteImageModel(preset: ImageModelPreset) {
        viewModelScope.launch {
            stop(); genJob?.join()
            imageEngine.unload()
            repo.deleteImageModel(preset)
            imageFilesVersion++
            if (store.current().imageModel == preset.id) store.update { it.copy(imageModel = null) }
            downloads.remove("img:" + preset.id)
        }
    }

    /** The image model to use: the selected one if present, else any available one. */
    fun currentImageModel(s: AppSettings = settings.value): ImageModelChoice? {
        val all = imageModelChoices()
        return all.firstOrNull { it.id == s.imageModel } ?: all.firstOrNull()
    }

    private fun createImage(prompt: String) {
        val s = settings.value
        val preset = currentImageModel(s)
        if (preset == null) { snackbar = "Download or import an image model first (Models → Image creation)"; return }
        input = ""
        val chatId = currentChatId ?: System.currentTimeMillis().toString().also { currentChatId = it }
        messages += ChatMessage(nextId++, Role.User, prompt, imageRequest = true)
        val reply = ChatMessage(nextId++, Role.Assistant, "", imageRequest = true).apply {
            streaming = true
            status = "Loading ${preset.title}…"
            progress = 0f
        }
        messages += reply
        generating = true
        val steps = (if (s.imageSteps > 0) s.imageSteps else preset.defaultSteps).coerceIn(1, preset.maxSteps)
        val cfg = if (s.imageCfg > 0f) s.imageCfg else preset.defaultCfg
        val sampler = if (s.imageSampler >= 0) s.imageSampler else preset.defaultSampler
        val size = preset.sizeFor(s.imageSize)
        val seed = kotlin.random.Random.nextLong(0, Int.MAX_VALUE.toLong())

        genJob = viewModelScope.launch {
            val progressJob = launch {
                while (true) {
                    kotlinx.coroutines.delay(300)
                    val p = imageEngine.progress() ?: continue
                    when (p.stage) {
                        "sampling" -> if (p.total > 0) {
                            reply.status = "Creating… step ${minOf(p.done + 1, p.total)} of ${p.total}"
                            // sampling is most of the work; leave the last 15% for decoding
                            reply.progress = 0.05f + 0.8f * p.done / p.total
                            if (p.done >= p.total) { reply.status = "Finishing…"; reply.progress = 0.9f }
                        }
                        "conditioning", "preparing" -> { reply.status = "Reading the prompt…"; reply.progress = 0.03f }
                    }
                }
            }
            val t0 = System.nanoTime()
            try {
                val img = imageEngine.generate(
                    preset.model, preset.llm, preset.taesd, s.effectiveThreads, prompt, s.imageNegative, size, size,
                    steps, cfg, seed, sampler, preset.scheduler,
                )
                progressJob.cancel()
                val saved = chatStore.saveMedia(chatId, DecodedMedia.Image(img.rgb, img.width, img.height))
                reply.savedMedia = listOf(saved)
                reply.attachments = listOf(saved.toAttachment(chatStore.dir(chatId)))
                reply.status = "%s · %d×%d · %d step%s · seed %d · %.1f s".format(
                    preset.title, img.width, img.height, steps, if (steps == 1) "" else "s", seed, (System.nanoTime() - t0) / 1e9,
                )
            } catch (e: CancellationException) {
                reply.status = "Stopped"
            } catch (e: Exception) {
                reply.error = true
                reply.status = null
                reply.text = e.message ?: "Image creation failed"
            } finally {
                progressJob.cancel()
                reply.progress = null
                reply.streaming = false
                generating = false
                withContext(NonCancellable) { runCatching { persistChat() } }
            }
        }
    }

    /** Imports an image-model file downloaded outside the app. */
    fun importImageModel(uri: Uri) {
        viewModelScope.launch {
            importProgress = 0f
            try {
                val what = repo.importImageFile(uri) { importProgress = it }
                val choice = imageModelChoices().firstOrNull { it.title == what || it.model.name == what }
                if (choice != null && store.current().imageModel == null) selectImageModel(choice.id)
                snackbar = "Imported $what"
                imageFilesVersion++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar = e.message ?: "Import failed"
            } finally {
                importProgress = null
            }
        }
    }

    /** bumped when image model files change, so the UI re-checks what's downloaded */
    var imageFilesVersion by mutableStateOf(0)
        private set

    /** Copies an image file into the phone's gallery (Pictures/LlamaChat). */
    fun saveToGallery(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "llamachat_${System.currentTimeMillis()}.jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LlamaChat")
                    }
                    val target = app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    app.contentResolver.openOutputStream(target)!!.use { out ->
                        app.contentResolver.openInputStream(uri)!!.use { it.copyTo(out) }
                    }
                }.isSuccess
            }
            snackbar = if (ok) "Saved to Pictures/LlamaChat" else "Could not save the image"
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
            if (usesIt) unloadModel()
            repo.unlinkFolder(uri)
            store.update {
                it.copy(
                    linkedFolders = it.linkedFolders - uri,
                    modelPath = if (usesIt) null else it.modelPath,
                    mmprojPath = if (usesIt) null else it.mmprojPath,
                )
            }
            refreshLastModel()
            refreshLocalModels()
        }
    }

    /** Deletes a model from app storage (files in linked folders are never deleted). */
    fun deleteModel(model: LocalModel) {
        if (model.inLinkedFolder) return
        viewModelScope.launch {
            val s = settings.value
            if (s.modelPath == model.ref || s.mmprojPath == model.ref) {
                unloadModel()
                store.update { it.copy(modelPath = null, mmprojPath = null) }
            }
            repo.delete(File(model.ref))
            refreshLastModel()
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

    // ---------------------------------------------------------------- benchmark

    data class BenchResult(val threads: Int, val promptTps: Float? = null, val genTps: Float? = null, val imageSec: Float? = null)

    val benchResults = mutableStateListOf<BenchResult>()
    var benchStatus by mutableStateOf<String?>(null)
        private set
    var benchRunning by mutableStateOf(false)
        private set
    private var benchJob: Job? = null

    /** Thread counts worth comparing: around the fast cores, up to all cores (max 8). */
    private fun benchThreadOptions(): List<Int> {
        val total = CpuInfo.totalCores.coerceAtMost(8)
        return (listOf(2, CpuInfo.fastCores, 4, 6, total)).filter { it in 1..total }.distinct().sorted()
    }

    private fun benchRow(threads: Int, f: (BenchResult) -> BenchResult) {
        val i = benchResults.indexOfFirst { it.threads == threads }
        if (i >= 0) benchResults[i] = f(benchResults[i]) else benchResults += f(BenchResult(threads))
    }

    /** Chat model: prompt processing (128 tokens) and generation (32 tokens) per thread count. */
    fun runChatBenchmark() {
        if (engine.state.value !is EngineState.Ready) { snackbar = "Load a chat model first"; return }
        if (benchRunning) return
        benchJob = viewModelScope.launch {
            stop(); genJob?.join()
            benchRunning = true
            try {
                benchStatus = "Warming up…"
                engine.bench(32, 4, CpuInfo.fastCores)
                for (t in benchThreadOptions()) {
                    benchStatus = "Chat model: testing $t threads…"
                    val r = engine.bench(128, 32, t) ?: error("Benchmark failed")
                    benchRow(t) { it.copy(promptTps = r.first, genTps = r.second) }
                }
                val best = benchResults.filter { it.genTps != null }.maxByOrNull { it.genTps!! }
                benchStatus = best?.let { "Fastest replies with ${it.threads} threads" }
            } catch (e: CancellationException) {
                benchStatus = "Stopped"
            } catch (e: Exception) {
                benchStatus = e.message ?: "Benchmark failed"
            } finally {
                // the chat's KV cache was cleared: the next message re-reads the conversation
                withContext(NonCancellable) {
                    engine.setThreads(settings.value.effectiveThreads)
                    contextUsage = engine.contextUsage()
                }
                benchRunning = false
            }
        }
    }

    /** Image model: one small (256×256) image per thread count. */
    fun runImageBenchmark() {
        val model = currentImageModel()
        if (model == null) { snackbar = "Download or import an image model first"; return }
        if (benchRunning) return
        benchJob = viewModelScope.launch {
            stop(); genJob?.join()
            benchRunning = true
            try {
                val steps = minOf(2, model.defaultSteps)
                for (t in benchThreadOptions()) {
                    benchStatus = "${model.title}: testing $t threads (256×256, $steps step${if (steps == 1) "" else "s"})…"
                    imageEngine.preload(model.model, model.llm, model.taesd, t) // loading isn't part of the timing
                    val t0 = System.nanoTime()
                    imageEngine.generate(
                        model.model, model.llm, model.taesd, t, "a lighthouse on a cliff at sunset", "", 256, 256,
                        steps, model.defaultCfg, 1L, model.defaultSampler, model.scheduler,
                    )
                    benchRow(t) { it.copy(imageSec = ((System.nanoTime() - t0) / 1e9).toFloat()) }
                }
                val best = benchResults.filter { it.imageSec != null }.minByOrNull { it.imageSec!! }
                benchStatus = best?.let { "Fastest images with ${it.threads} threads" }
            } catch (e: CancellationException) {
                benchStatus = "Stopped"
            } catch (e: Exception) {
                benchStatus = e.message ?: "Benchmark failed"
            } finally {
                benchRunning = false
            }
        }
    }

    fun stopBenchmark() {
        imageEngine.cancel()
        benchJob?.cancel()
    }

    fun applyThreads(threads: Int) {
        updateSettings(reloadNeeded = true) { it.copy(threads = threads) }
        snackbar = "Using $threads threads"
    }

    // ---------------------------------------------------------------- GPU (experimental build)

    var gpuDrivers by mutableStateOf<List<GpuDriver>>(emptyList())
        private set

    /** the Vulkan driver chosen in settings differs from the one this process loaded */
    val gpuRestartNeeded: Boolean
        get() = (engine.initializedDriverId != null && engine.initializedDriverId != (settings.value.gpuDriver ?: "")) ||
            (engine.initializedVulkanCompat != null && engine.initializedVulkanCompat != settings.value.vulkanCompat)

    fun refreshGpuDrivers() {
        if (!BuildConfig.GPU_EXPERIMENTAL) return
        viewModelScope.launch { gpuDrivers = driverStore.list() }
    }

    /** Tells the engine which custom driver to load when it initializes (once per process). */
    private suspend fun prepareGpuDriver() {
        if (!BuildConfig.GPU_EXPERIMENTAL) return
        val s = store.current()
        engine.driverForInit = s.gpuDriver?.let { driverStore.get(it) }
        engine.vulkanCompatForInit = s.vulkanCompat
    }

    /** Loads the GPU driver now (without a model) so the GPU can be shown in settings. */
    fun probeGpu() {
        if (!BuildConfig.GPU_EXPERIMENTAL) return
        viewModelScope.launch { prepareGpuDriver(); engine.initialize() }
    }

    fun importGpuDriver(uri: Uri) {
        viewModelScope.launch {
            try {
                val d = driverStore.import(uri)
                refreshGpuDrivers()
                store.update { it.copy(gpuDriver = d.id) }
                snackbar = "Imported ${d.name}. Restart the app to use it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar = e.message ?: "Could not import that driver"
            }
        }
    }

    fun selectGpuDriver(id: String?) {
        viewModelScope.launch { store.update { it.copy(gpuDriver = id) } }
    }

    fun deleteGpuDriver(id: String) {
        viewModelScope.launch {
            if (store.current().gpuDriver == id) store.update { it.copy(gpuDriver = null) }
            driverStore.delete(id)
            refreshGpuDrivers()
        }
    }

    /** A Vulkan driver can only be loaded once per process: restart to apply a new choice. */
    fun restartApp() {
        val app = getApplication<Application>()
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    // ---------------------------------------------------------------- chats

    private suspend fun refreshChats() { chats = chatStore.list() }

    fun newChat() {
        viewModelScope.launch { startNewChat() }
    }

    private suspend fun startNewChat() {
        stop(); genJob?.join()
        messages.clear()
        pendingAttachments.clear()
        currentChatId = null
        engine.resetChat()
        contextUsage = engine.contextUsage()
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
                    imageRequest = m.kind == "image",
                )
            }
            if (engine.state.value is EngineState.Ready) restoreIntoEngine()
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            // wait for a running reply to stop (and save) before deleting, or it re-creates the chat
            if (id == currentChatId) startNewChat()
            chatStore.delete(id)
            refreshChats()
        }
    }

    /** Puts the visible conversation into the engine; it is evaluated with the next message. */
    private suspend fun restoreIntoEngine() {
        val id = currentChatId ?: return
        fun answered(m: ChatMessage?) = m != null && m.role == Role.Assistant && !m.error && m.text.isNotEmpty()
        // only complete turns: a user message whose media failed to decode (or that was stopped
        // before any reply) would otherwise come back as an empty / dangling user turn
        // image-creation prompts and results aren't part of the conversation with the chat model
        val chatTurns = messages.filter { !it.imageRequest }
        val restored = chatTurns.filterIndexed { i, m ->
            if (m.role == Role.User) answered(chatTurns.getOrNull(i + 1)) else answered(m)
        }.map { m ->
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
                        kind = if (m.imageRequest) "image" else "",
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
            // the composer keys thumbnails by URI: the same file twice would crash the LazyRow
            if (pendingAttachments.any { it.uri == uri }) continue
            val type = resolver.getType(uri) ?: ""
            val kind = if (type.startsWith("video")) AttachmentKind.Video else AttachmentKind.Image
            pendingAttachments += Attachment(uri, kind)
        }
    }

    fun removeAttachment(a: Attachment) { pendingAttachments.remove(a) }

    fun stop() {
        imageEngine.cancel() // a running image generation only notices this, not coroutine cancellation
        genJob?.cancel()
    }

    fun send() {
        if (imageMode) {
            val prompt = input.trim()
            if (prompt.isNotEmpty() && !generating) createImage(prompt)
            return
        }
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
                val promptStart = System.nanoTime()
                var promptSec = 0.0
                engine.gpuStats() // reset the counters
                engine.generate(user.prompt, media).onCompletion { progressJob.cancel() }.collect { piece ->
                    if (tokens == 0) {
                        start = System.nanoTime()
                        promptSec = (start - promptStart) / 1e9
                        progressJob.cancel()
                    }
                    tokens++
                    reply.text += piece
                    if (tokens % 8 == 0) reply.status = tps(tokens, start)
                }
                // speed, prompt processing time and (experimental build) GPU work: handy to compare CPU vs GPU
                val gpu = engine.gpuStats()
                reply.status = listOfNotNull(
                    if (tokens > 1) tps(tokens, start) else null,
                    if (promptSec > 0) "prompt %.1f s".format(promptSec) else null,
                    gpu.ifEmpty { null },
                ).joinToString(" · ").ifEmpty { null }
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
