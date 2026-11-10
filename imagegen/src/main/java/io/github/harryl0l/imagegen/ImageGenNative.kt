package io.github.harryl0l.imagegen

import java.io.File

/**
 * JNI bindings to stable-diffusion.cpp (see imagegen/src/main/cpp/sd_jni.cpp).
 * load / generate / unload: one thread. progress / cancel: any thread.
 */
object ImageGenNative {
    /** Whether this CPU can run the library (built for armv8.2-a + dot product + fp16). */
    val isSupported: Boolean by lazy {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi != "arm64-v8a") return@lazy abi == "x86_64"
        val features = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        // every core must have them; checking that the feature words appear is enough in practice
        "asimddp" in features && "asimdhp" in features
    }

    private val loaded: Boolean by lazy {
        isSupported && runCatching { System.loadLibrary("imagegen") }.isSuccess
    }

    fun available(): Boolean = loaded

    /**
     * @param modelPath a full checkpoint, or with [llmPath] only the diffusion model (e.g. Anima)
     * @param llmPath LLM text encoder for models that use one, else null
     * @return null on success, else an error
     */
    external fun load(modelPath: String, llmPath: String?, taesdPath: String?, nThreads: Int, gpu: Boolean): String?

    /** engine log lines added after line number [since]: "<new count>\n<line>\n…" */
    external fun logs(since: Long): String

    /** whether this build has the Vulkan backend (experimental build) */
    external fun hasGpu(): Boolean
    external fun unload()

    /** RGB pixels (width * height * 3) or null on failure / cancel (see [lastError]) */
    external fun generate(
        prompt: String, negativePrompt: String, width: Int, height: Int,
        steps: Int, cfgScale: Float, seed: Long,
        /** stable-diffusion.cpp sample_method_t: 1 = Euler A, 5 = DPM++ 2M, 9 = LCM */
        sampler: Int,
        /** stable-diffusion.cpp scheduler_t, -1 = the model's default */
        scheduler: Int,
    ): ByteArray?

    /** Quantizes a model file to GGUF (streaming). type: sd_type_t, 8 = Q8_0, 13 = Q5_K. @return null on success, else an error */
    external fun convert(inPath: String, outPath: String, type: Int, nThreads: Int): String?

    /** "stage|completed|total" while generating, "" otherwise */
    external fun progress(): String
    external fun cancel()
    external fun lastError(): String
}
