package io.github.harryl0l.llamachat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A ready-made vision model that can be downloaded from Hugging Face. */
data class ModelPreset(
    val id: String,
    val title: String,
    val summary: String,
    val repo: String,
    val modelFile: String,
    val mmprojFile: String,
    val sizeBytes: Long,
) {
    fun url(file: String) = "https://huggingface.co/$repo/resolve/main/$file?download=true"
}

val MODEL_PRESETS = listOf(
    ModelPreset(
        "smolvlm2-500m", "SmolVLM2 500M Video",
        "Tiny and fast. Trained for video. Good first try on any phone.",
        "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF",
        "SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        436_808_704L + 108_785_184L,
    ),
    ModelPreset(
        "qwen3-vl-2b", "Qwen3-VL 2B",
        "Strong image & video understanding with timestamps. Needs ~4 GB RAM.",
        "ggml-org/Qwen3-VL-2B-Instruct-GGUF",
        "Qwen3-VL-2B-Instruct-Q8_0.gguf", "mmproj-Qwen3-VL-2B-Instruct-Q8_0.gguf",
        1_834_427_296L + 445_053_056L,
    ),
    ModelPreset(
        "qwen2.5-vl-3b", "Qwen2.5-VL 3B",
        "Great at documents, charts and OCR. Needs ~5 GB RAM.",
        "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF",
        "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        1_929_901_056L + 844_757_728L,
    ),
    ModelPreset(
        "gemma-3-4b", "Gemma 3 4B",
        "Best general chat quality of the bunch. Needs ~6 GB RAM.",
        "ggml-org/gemma-3-4b-it-GGUF",
        "gemma-3-4b-it-Q4_K_M.gguf", "mmproj-model-f16.gguf",
        2_489_757_856L + 851_251_104L,
    ),
)

data class LocalModel(val file: File, val isProjector: Boolean) {
    val name: String get() = file.name
    val size: Long get() = file.length()
}

class ModelRepository(private val context: Context) {
    val modelsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun presetDir(preset: ModelPreset) = File(modelsDir, preset.id)

    fun isDownloaded(preset: ModelPreset): Boolean =
        File(presetDir(preset), preset.modelFile).exists() && File(presetDir(preset), preset.mmprojFile).exists()

    fun presetFiles(preset: ModelPreset): Pair<File, File> =
        File(presetDir(preset), preset.modelFile) to File(presetDir(preset), preset.mmprojFile)

    fun localModels(): List<LocalModel> =
        modelsDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .map { LocalModel(it, it.name.contains("mmproj", ignoreCase = true)) }
            .sortedBy { it.name.lowercase() }
            .toList()

    /** Downloads both files of a preset. Resumes partial downloads. [onProgress] gets 0..1. */
    suspend fun download(preset: ModelPreset, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = presetDir(preset).apply { mkdirs() }
        val files = listOf(preset.mmprojFile, preset.modelFile)
        var doneBefore = 0L
        for (name in files) {
            val target = File(dir, name)
            if (target.exists()) {
                doneBefore += target.length()
                continue
            }
            val base = doneBefore
            downloadFile(preset.url(name), target) { bytes ->
                onProgress(((base + bytes).toFloat() / preset.sizeBytes).coerceIn(0f, 1f))
            }
            doneBefore += target.length()
        }
        onProgress(1f)
    }

    private suspend fun downloadFile(url: String, target: File, onBytes: (Long) -> Unit) {
        val part = File(target.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "LlamaChat-Android")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) existing = 0L // server ignored the range
            else if (code != HttpURLConnection.HTTP_PARTIAL) error("HTTP $code while downloading ${target.name}")
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, existing > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = existing
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - lastReport > (1 shl 20)) {
                            onBytes(total); lastReport = total
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        check(part.renameTo(target)) { "Could not save ${target.name}" }
    }

    /** Copies a user-picked GGUF into app storage (llama.cpp needs a real file path to mmap). */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "model.gguf"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                size = c.getLong(1)
            }
        }
        require(name.endsWith(".gguf", ignoreCase = true)) { "Please pick a .gguf file" }
        val dir = File(modelsDir, "imported").apply { mkdirs() }
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        resolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (size > 0) onProgress(total.toFloat() / size)
                }
            }
        }
        target.delete()
        check(part.renameTo(target)) { "Could not save $name" }
        target
    }

    fun delete(file: File) {
        file.delete()
        file.parentFile?.takeIf { it != modelsDir && it.list().isNullOrEmpty() }?.delete()
    }
}
