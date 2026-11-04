package io.github.harryl0l.llamachat.engine

/**
 * Raw JNI bindings to llama.cpp + libmtmd (see app/src/main/cpp/llama_jni.cpp).
 * Every call must happen on the single engine thread owned by [LlamaEngine].
 */
internal object LlamaNative {
    init {
        System.loadLibrary("llamachat")
    }

    external fun init(nativeLibDir: String)
    external fun systemInfo(): String

    /** @return null on success, otherwise an error message */
    external fun load(modelPath: String, mmprojPath: String?, nCtx: Int, nThreads: Int): String?
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

    /** [used, total] tokens */
    external fun contextUsage(): IntArray
}
