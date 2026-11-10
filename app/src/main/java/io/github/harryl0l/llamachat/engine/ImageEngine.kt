package io.github.harryl0l.llamachat.engine

import io.github.harryl0l.imagegen.ImageGenNative
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Progress of a running image generation. */
data class ImageProgress(val stage: String, val done: Long, val total: Long)

class ImageResult(val rgb: ByteArray, val width: Int, val height: Int, val onGpu: Boolean)

/**
 * Text-to-image with stable-diffusion.cpp (the :imagegen module). The model stays loaded between
 * images for fast repeats; [unload] frees it.
 */
class ImageEngine {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "image-engine") }.asCoroutineDispatcher()

    private var loadedKey: String? = null

    val available: Boolean get() = ImageGenNative.available()

    /** whether this build can create images on the GPU (experimental build) */
    val gpuAvailable: Boolean get() = available && ImageGenNative.hasGpu()

    /** whether the loaded model runs on the GPU */
    var onGpu = false
        private set

    /** why the last GPU load failed (then the CPU was used), else null */
    var gpuError: String? = null
        private set

    /** Loads the model (if not loaded with these settings) without generating. */
    suspend fun preload(model: File, llm: File?, taesd: File?, threads: Int, gpu: Boolean = false) =
        withContext(dispatcher) { ensureLoaded(model, llm, taesd, threads, gpu) }

    /** Loads the model; on the GPU if asked and possible, else (or if that fails) on the CPU. */
    private fun ensureLoaded(model: File, llm: File?, taesd: File?, threads: Int, gpu: Boolean) {
        if (!available) throw EngineException("Image creation isn't supported on this device's CPU")
        val wantGpu = gpu && gpuAvailable
        val key = model.absolutePath + "|" + llm?.absolutePath + "|" + taesd?.absolutePath + "|" + threads + "|" + wantGpu
        if (loadedKey != key) {
            loadedKey = null
            gpuError = null
            onGpu = false
            if (wantGpu) {
                val err = ImageGenNative.load(model.absolutePath, llm?.absolutePath, taesd?.absolutePath, threads, true)
                if (err == null) { onGpu = true; loadedKey = key; return }
                gpuError = err
            }
            ImageGenNative.load(model.absolutePath, llm?.absolutePath, taesd?.absolutePath, threads, false)
                ?.let { throw EngineException(it) }
            loadedKey = key
        }
    }

    suspend fun generate(
        model: File, llm: File?, taesd: File?, threads: Int,
        prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        sampler: Int, scheduler: Int = -1, gpu: Boolean = false,
    ): ImageResult = withContext(dispatcher) {
        ensureLoaded(model, llm, taesd, threads, gpu)
        val rgb = ImageGenNative.generate(prompt, negative, width, height, steps, cfg, seed, sampler, scheduler)
            ?: throw EngineException(ImageGenNative.lastError().ifEmpty { "Image generation failed" })
        ImageResult(rgb, width, height, onGpu)
    }

    /** Any thread. null when idle. */
    fun progress(): ImageProgress? {
        if (!available) return null
        val p = ImageGenNative.progress().split('|')
        if (p.size < 3) return null
        return ImageProgress(p[0], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
    }

    /** Any thread: the engine's log lines after line number [since], and the new line count. */
    fun logsSince(since: Long): Pair<Long, List<String>> {
        if (!available) return since to emptyList()
        val lines = ImageGenNative.logs(since).split('\n')
        return (lines.first().toLongOrNull() ?: since) to lines.drop(1)
    }

    /** Any thread: stop the running generation as soon as possible. */
    fun cancel() { if (available) ImageGenNative.cancel() }

    val isLoaded: Boolean get() = loadedKey != null

    /** Quantizes a model file to GGUF on the engine thread (unloads the current model first to free memory). */
    suspend fun convert(input: File, output: File, type: Int, threads: Int) = withContext(dispatcher) {
        if (!available) throw EngineException("Image creation isn't supported on this device's CPU")
        if (loadedKey != null) { ImageGenNative.unload(); loadedKey = null }
        ImageGenNative.convert(input.absolutePath, output.absolutePath, type, threads)?.let { throw EngineException(it) }
    }

    suspend fun unload() = withContext(dispatcher) {
        if (available && loadedKey != null) ImageGenNative.unload()
        loadedKey = null
    }
}
