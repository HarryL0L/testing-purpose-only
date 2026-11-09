package io.github.harryl0l.llamachat.engine

import io.github.harryl0l.imagegen.ImageGenNative
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Progress of a running image generation. */
data class ImageProgress(val stage: String, val done: Long, val total: Long)

class ImageResult(val rgb: ByteArray, val width: Int, val height: Int)

/**
 * Text-to-image with stable-diffusion.cpp (the :imagegen module). The model stays loaded between
 * images for fast repeats; [unload] frees it.
 */
class ImageEngine {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "image-engine") }.asCoroutineDispatcher()

    private var loadedKey: String? = null

    val available: Boolean get() = ImageGenNative.available()

    /** Loads the model (if not loaded with these settings) without generating. */
    suspend fun preload(model: File, taesd: File?, threads: Int) = withContext(dispatcher) { ensureLoaded(model, taesd, threads) }

    private fun ensureLoaded(model: File, taesd: File?, threads: Int) {
        if (!available) throw EngineException("Image creation isn't supported on this device's CPU")
        val key = model.absolutePath + "|" + taesd?.absolutePath + "|" + threads
        if (loadedKey != key) {
            loadedKey = null
            ImageGenNative.load(model.absolutePath, taesd?.absolutePath, threads)?.let { throw EngineException(it) }
            loadedKey = key
        }
    }

    suspend fun generate(
        model: File, taesd: File?, threads: Int,
        prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        sampler: Int,
    ): ImageResult = withContext(dispatcher) {
        ensureLoaded(model, taesd, threads)
        val rgb = ImageGenNative.generate(prompt, negative, width, height, steps, cfg, seed, sampler)
            ?: throw EngineException(ImageGenNative.lastError().ifEmpty { "Image generation failed" })
        ImageResult(rgb, width, height)
    }

    /** Any thread. null when idle. */
    fun progress(): ImageProgress? {
        if (!available) return null
        val p = ImageGenNative.progress().split('|')
        if (p.size < 3) return null
        return ImageProgress(p[0], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
    }

    /** Any thread: stop the running generation as soon as possible. */
    fun cancel() { if (available) ImageGenNative.cancel() }

    val isLoaded: Boolean get() = loadedKey != null

    suspend fun unload() = withContext(dispatcher) {
        if (available && loadedKey != null) ImageGenNative.unload()
        loadedKey = null
    }
}
