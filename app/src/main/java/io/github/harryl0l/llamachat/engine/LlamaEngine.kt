package io.github.harryl0l.llamachat.engine

import android.content.Context
import io.github.harryl0l.llamachat.media.DecodedMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class ModelInfo(
    val name: String,
    val description: String,
    val vision: Boolean,
    val audio: Boolean,
    val video: Boolean,
    val contextSize: Int,
)

sealed interface EngineState {
    data object NoModel : EngineState
    data class Loading(val name: String) : EngineState
    data class Ready(val info: ModelInfo) : EngineState
    data class Failed(val message: String) : EngineState
}

data class SamplingConfig(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val maxTokens: Int = 1024,
)

/** Video layout recommended by the loaded model (see mtmd_get_video_config). */
data class VideoConfig(val template: Int, val fps: Float, val nFrames: Int, val width: Int, val height: Int)

class EngineException(message: String) : Exception(message)

/** GPU modes (experimental build) */
const val GPU_CPU = 0
const val GPU_VULKAN = 1
const val GPU_MALI = 2

/** Where to load a vision projector from; [fallback] provides another path if the first fails. */
class ProjectorSpec(val path: String, val fallback: (suspend () -> String)? = null)

/** What to load: a file path, or an open file descriptor (model used in place). */
class ModelSpec(
    val name: String,
    val path: String?,
    val fd: Int = -1,
    val projector: ProjectorSpec? = null,
)

/** A saved message to restore; [media] belong to user messages, in order. */
class RestoredMessage(val role: String, val text: String, val media: List<DecodedMedia> = emptyList())

/**
 * Owns the native llama.cpp state. All native calls run on one dedicated thread.
 */
class LlamaEngine(context: Context) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llama-engine").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

    private val _state = MutableStateFlow<EngineState>(EngineState.NoModel)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private var initialized = false

    val mediaMarker: String by lazy { LlamaNative.mediaMarker() }

    /**
     * Custom Vulkan driver to use when the engine initializes (experimental build). The driver is
     * loaded once per process, so changing it needs an app restart.
     */
    @Volatile var driverForInit: io.github.harryl0l.llamachat.data.GpuDriver? = null

    /** Vulkan compatibility mode for init (experimental build); fixed for the process lifetime */
    @Volatile var vulkanCompatForInit: Boolean = true

    /** the compatibility mode the engine was initialized with, null before init */
    var initializedVulkanCompat: Boolean? = null
        private set

    /** id of the driver the engine was initialized with ("" = system driver), null before init */
    var initializedDriverId: String? = null
        private set

    private val _gpuStatus = MutableStateFlow("")
    val gpuStatus: StateFlow<String> = _gpuStatus.asStateFlow()

    private fun ensureInit() {
        if (!initialized) {
            val d = driverForInit
            val status = LlamaNative.init(nativeLibDir, d?.dir?.absolutePath, d?.library, vulkanCompatForInit)
            initializedDriverId = d?.id ?: ""
            initializedVulkanCompat = vulkanCompatForInit
            initialized = true
            val gpus = LlamaNative.gpuInfo()
            val mali = LlamaNative.enableMali(false)
            _gpuStatus.value = listOf(status, gpus.ifEmpty { if (status.isNotEmpty()) "No Vulkan GPU found" else "" }, mali)
                .filter { it.isNotEmpty() }.joinToString("\n")
        }
    }

    /** GPU work since the last call ("" when nothing ran on the Mali backend). */
    suspend fun gpuStats(): String = withContext(dispatcher) { if (initialized) LlamaNative.maliStats() else "" }

    /** Initializes the backends (loads the GPU driver) without loading a model. */
    suspend fun initialize() = withContext(dispatcher) { ensureInit() }

    suspend fun load(
        spec: ModelSpec,
        contextSize: Int,
        threads: Int,
        sampling: SamplingConfig,
        systemPrompt: String,
        thinking: Boolean,
        repack: Boolean,
        gpuMode: Int = GPU_CPU,
    ) = withContext(dispatcher) {
        ensureInit()
        _state.value = EngineState.Loading(spec.name)
        LlamaNative.setSampling(sampling.temperature, sampling.topP, sampling.topK, sampling.minP, sampling.maxTokens)
        LlamaNative.setSystemPrompt(systemPrompt)
        LlamaNative.setThinking(thinking)
        var error = LlamaNative.load(spec.path, spec.fd, contextSize, threads, repack, gpuMode)
        if (error == null && spec.projector != null) {
            error = LlamaNative.loadProjector(spec.projector.path, threads)
            val fallback = spec.projector.fallback
            if (error != null && fallback != null) {
                error = try {
                    LlamaNative.loadProjector(fallback(), threads)
                } catch (e: Exception) {
                    e.message ?: "Could not read the projector file"
                }
            }
            if (error != null) LlamaNative.unload()
        }
        if (error != null) {
            _state.value = EngineState.Failed(error)
            return@withContext
        }
        val caps = LlamaNative.capabilities()
        _state.value = EngineState.Ready(
            ModelInfo(
                name = spec.name,
                description = LlamaNative.modelDescription(),
                vision = caps and 1 != 0,
                audio = caps and 2 != 0,
                video = caps and 4 != 0,
                contextSize = contextSize,
            )
        )
    }

    /** 0..1 while the prompt (text + media) is being processed, -1 otherwise. Any thread. */
    fun promptProgress(): Float = if (initialized) LlamaNative.promptProgress() else -1f

    /** Any thread: the engine's log lines after line number [since], and the new line count. */
    fun logsSince(since: Long): Pair<Long, List<String>> {
        if (!initialized) return since to emptyList()
        val lines = LlamaNative.logs(since).split('\n')
        return (lines.first().toLongOrNull() ?: since) to lines.drop(1)
    }

    suspend fun setThinking(enabled: Boolean) = withContext(dispatcher) {
        if (initialized) LlamaNative.setThinking(enabled)
    }

    /** Puts a saved conversation back into the engine; it is evaluated with the next message. */
    suspend fun restore(messages: List<RestoredMessage>) = withContext(dispatcher) {
        if (!initialized) return@withContext
        LlamaNative.clearMedia()
        val roles = ArrayList<String>()
        val contents = ArrayList<String>()
        for (m in messages) {
            if (m.role == "user") {
                val usable = m.media.filter { addMedia(it) }
                roles += "user"
                contents += userContent(usable.size, m.text)
            } else {
                roles += m.role
                contents += m.text
            }
        }
        LlamaNative.restoreChat(roles.toTypedArray(), contents.toTypedArray())
    }

    private fun addMedia(m: DecodedMedia): Boolean = when (m) {
        is DecodedMedia.Image -> LlamaNative.addImage(m.rgb, m.width, m.height)
        is DecodedMedia.Video -> LlamaNative.addVideo(m.frames, m.width, m.height, m.timestamps, m.template)
    }

    private fun userContent(nMedia: Int, text: String) = buildString {
        repeat(nMedia) { append(mediaMarker).append('\n') }
        append(text)
    }

    suspend fun unload() = withContext(dispatcher) {
        if (initialized) LlamaNative.unload()
        _state.value = EngineState.NoModel
    }

    suspend fun updateSampling(sampling: SamplingConfig, systemPrompt: String) = withContext(dispatcher) {
        LlamaNative.setSampling(sampling.temperature, sampling.topP, sampling.topK, sampling.minP, sampling.maxTokens)
        LlamaNative.setSystemPrompt(systemPrompt)
    }

    suspend fun videoConfig(width: Int, height: Int, nFrames: Int, fps: Float): VideoConfig = withContext(dispatcher) {
        val c = LlamaNative.videoConfig(width, height, nFrames, fps)
        VideoConfig(c[0].toInt(), c[1], c[2].toInt(), c[3].toInt(), c[4].toInt())
    }

    /** (prompt tok/s, generation tok/s) of the loaded model with [threads], or null */
    suspend fun bench(pp: Int, tg: Int, threads: Int): Pair<Float, Float>? = withContext(dispatcher) {
        if (!initialized) null else LlamaNative.bench(pp, tg, threads)?.let { it[0] to it[1] }
    }

    suspend fun setThreads(threads: Int) = withContext(dispatcher) { if (initialized) LlamaNative.setThreads(threads) }

    suspend fun resetChat() = withContext(dispatcher) {
        if (initialized) LlamaNative.resetChat()
    }

    suspend fun contextUsage(): Pair<Int, Int> = withContext(dispatcher) {
        if (!initialized) 0 to 0 else LlamaNative.contextUsage().let { it[0] to it[1] }
    }

    /**
     * Sends a user turn and streams the reply. [media] items are placed before the text,
     * in order. Cancelling the collector stops generation; the partial reply is kept in history.
     */
    fun generate(text: String, media: List<DecodedMedia>): Flow<String> = flow {
        LlamaNative.clearMedia()
        for (m in media) {
            if (!addMedia(m)) throw EngineException("This model can't take that attachment (no vision projector loaded?)")
        }
        LlamaNative.prompt(userContent(media.size, text))?.let { throw EngineException(it) }
        // the template already opened a <think> block: surface it so the UI can show reasoning
        if (LlamaNative.promptOpensThinking()) emit("<think>")
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val piece = LlamaNative.nextToken() ?: break
                if (piece.isNotEmpty()) emit(piece)
            }
        } finally {
            LlamaNative.finishTurn()
        }
    }.flowOn(dispatcher)
}
