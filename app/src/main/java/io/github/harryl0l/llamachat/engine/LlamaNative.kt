package io.github.harryl0l.llamachat.engine

/**
 * Raw JNI bindings to llama.cpp + libmtmd (see app/src/main/cpp/llama_jni.cpp).
 * Every call must happen on the single engine thread owned by [LlamaEngine].
 */
internal object LlamaNative {
    init {
        System.loadLibrary("llamachat")
    }

    /**
     * Loads the ggml backends. In the experimental build, [driverDir]/[driverName] select a custom
     * Vulkan driver loaded through libadrenotools (null: system driver). Fixed for the process
     * lifetime. @return a status line about the GPU driver ("" in the CPU-only build)
     */
    external fun init(nativeLibDir: String, driverDir: String?, driverName: String?): String

    /** GPU devices ggml found, one per line ("" if none). */
    external fun gpuInfo(): String
    external fun systemInfo(): String

    /**
     * Loads the language model from [modelPath], or from the open file descriptor [fd] when >= 0
     * (used in place, e.g. a file in a folder the user linked). [repack] enables CPU weight repacking.
     * @return null on success, else an error
     */
    external fun load(modelPath: String?, fd: Int, nCtx: Int, nThreads: Int, repack: Boolean, gpuMode: Int): String?

    /** Registers the experimental Mali (OpenCL) backend. @return status ("" if not built in) */
    external fun enableMali(anyDevice: Boolean): String

    /** "n GPU matmuls, x ms" since the last call (Mali backend), "" if none */
    external fun maliStats(): String

    /** Loads the vision projector for the loaded model. @return null on success, else an error */
    external fun loadProjector(path: String, nThreads: Int): String?
    external fun unload()

    /** bit 0 = vision, bit 1 = audio, bit 2 = video */
    external fun capabilities(): Int
    external fun modelDescription(): String
    external fun mediaMarker(): String

    external fun setSampling(temp: Float, topP: Float, topK: Int, minP: Float, maxTokens: Int)
    external fun setSystemPrompt(prompt: String)

    /** [template, fps, nFrames, width, height] – 0 means "no preference" */
    external fun videoConfig(width: Int, height: Int, nFrames: Int, fps: Float): FloatArray

    external fun addImage(rgb: ByteArray, width: Int, height: Int): Boolean
    external fun addVideo(frames: ByteArray, width: Int, height: Int, timestamps: FloatArray, template: Int): Boolean
    external fun clearMedia()

    /** @return null on success, otherwise an error message */
    external fun prompt(text: String): String?

    /** next text piece, "" for a partial UTF-8 char, null when generation is done */
    external fun nextToken(): String?
    external fun finishTurn()
    external fun resetChat()

    /** 0..1 while a prompt is processed, -1 otherwise. Safe to call from any thread. */
    external fun promptProgress(): Float

    external fun setThinking(enabled: Boolean)
    external fun promptOpensThinking(): Boolean

    /** Restores a saved chat (not evaluated until the next prompt). Media added before belong to it. */
    external fun restoreChat(roles: Array<String>, contents: Array<String>)

    /** [prompt tok/s, generation tok/s] for pp / tg tokens with n threads; clears the KV cache */
    external fun bench(pp: Int, tg: Int, threads: Int): FloatArray?
    external fun setThreads(threads: Int)

    /** [used, total] tokens */
    external fun contextUsage(): IntArray
}
