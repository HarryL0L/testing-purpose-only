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

    /** @return null on success, else an error */
    external fun load(modelPath: String, taesdPath: String?, nThreads: Int): String?
    external fun unload()

    /** RGB pixels (width * height * 3) or null on failure / cancel (see [lastError]) */
    external fun generate(
        prompt: String, negativePrompt: String, width: Int, height: Int,
        steps: Int, cfgScale: Float, seed: Long,
    ): ByteArray?

    /** "stage|completed|total" while generating, "" otherwise */
    external fun progress(): String
    external fun cancel()
    external fun lastError(): String
}
