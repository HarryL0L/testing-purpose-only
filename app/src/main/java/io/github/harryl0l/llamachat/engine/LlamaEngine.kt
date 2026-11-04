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
import java.io.File
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

    private fun ensureInit() {
        if (!initialized) {
            LlamaNative.init(nativeLibDir)
            initialized = true
        }
    }

    suspend fun load(
        model: File,
        mmproj: File?,
        contextSize: Int,
        threads: Int,
        sampling: SamplingConfig,
        systemPrompt: String,
    ) = withContext(dispatcher) {
        ensureInit()
        _state.value = EngineState.Loading(model.nameWithoutExtension)
        LlamaNative.setSampling(sampling.temperature, sampling.topP, sampling.topK, sampling.minP, sampling.maxTokens)
        LlamaNative.setSystemPrompt(systemPrompt)
        val error = LlamaNative.load(model.absolutePath, mmproj?.absolutePath, contextSize, threads)
        if (error != null) {
            _state.value = EngineState.Failed(error)
            return@withContext
        }
        val caps = LlamaNative.capabilities()
        _state.value = EngineState.Ready(
            ModelInfo(
                name = model.nameWithoutExtension,
                description = LlamaNative.modelDescription(),
                vision = caps and 1 != 0,
                audio = caps and 2 != 0,
                video = caps and 4 != 0,
                contextSize = contextSize,
            )
        )
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
        val prompt = buildString {
            for (m in media) {
                val ok = when (m) {
                    is DecodedMedia.Image -> LlamaNative.addImage(m.rgb, m.width, m.height)
                    is DecodedMedia.Video -> LlamaNative.addVideo(m.frames, m.width, m.height, m.timestamps, m.template)
                }
                if (!ok) throw EngineException("This model can't take that attachment (no vision projector loaded?)")
                append(mediaMarker)
                append('\n')
            }
            append(text)
        }
        LlamaNative.prompt(prompt)?.let { throw EngineException(it) }
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
