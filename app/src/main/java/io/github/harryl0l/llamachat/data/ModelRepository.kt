package io.github.harryl0l.llamachat.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

/**
 * A GGUF the app can load. [ref] is an absolute path (app storage) or a content:// URI of a file
 * in a folder the user linked (used in place, never copied or deleted by the app).
 */
/** A text-to-image model for stable-diffusion.cpp. */
data class ImageModelPreset(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val file: String,
    val sizeBytes: Long,
    val defaultSteps: Int,
    val maxSteps: Int,
    val cfgScale: Float,
    /** small replacement decoder (TAESD): ~10x faster final decoding, near identical images */
    val taesd: TaesdFile? = null,
)

data class TaesdFile(val url: String, val file: String, val sizeBytes: Long)

private val TAESD = TaesdFile(
    "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors?download=true",
    "taesd.safetensors", 9_793_292L,
)

val IMAGE_PRESETS = listOf(
    ImageModelPreset(
        "sdxs-512", "SDXS-512",
        "Tiny one-step model. Fastest (about 6 s on a desktop CPU, several times that on a phone); simpler images.",
        "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf?download=true",
        "sdxs-512-tinySDdistilled_Q8_0.gguf", 682_847_200L,
        defaultSteps = 1, maxSteps = 2, cfgScale = 1.0f,
    ),
    ImageModelPreset(
        "sd-turbo", "SD-Turbo",
        "Better images in 1–4 steps (about 15 s for 2 steps on a desktop CPU). Uses the TAESD fast decoder.",
        "https://huggingface.co/Green-Sky/SD-Turbo-GGUF/resolve/main/sd_turbo-f16-q8_0.gguf?download=true",
        "sd_turbo-f16-q8_0.gguf", 2_023_745_376L,
        defaultSteps = 2, maxSteps = 4, cfgScale = 1.0f, taesd = TAESD,
    ),
)

data class LocalModel(
    val ref: String,
    val name: String,
    val size: Long,
    val isProjector: Boolean,
    val folder: String? = null, // display name of the linked folder, null for app storage
    val folderUri: String? = null,
) {
    val inLinkedFolder: Boolean get() = folder != null
}

data class LinkedFolder(val uri: String, val name: String)

private fun isProjectorName(name: String) = name.contains("mmproj", ignoreCase = true)

private const val HTTP_RANGE_NOT_SATISFIABLE = 416

class ModelRepository(private val context: Context) {
    val modelsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun presetDir(preset: ModelPreset) = File(modelsDir, preset.id)

    fun isDownloaded(preset: ModelPreset): Boolean =
        File(presetDir(preset), preset.modelFile).exists() && File(presetDir(preset), preset.mmprojFile).exists()

    fun presetFiles(preset: ModelPreset): Pair<File, File> =
        File(presetDir(preset), preset.modelFile) to File(presetDir(preset), preset.mmprojFile)

    /** GGUFs in app storage plus those found in the linked folders. */
    fun localModels(folders: Set<String>): List<LocalModel> {
        val own = modelsDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .map { LocalModel(it.absolutePath, it.name, it.length(), isProjectorName(it.name)) }
            .toList()
        val linked = folders.flatMap { uri -> runCatching { scanFolder(Uri.parse(uri)) }.getOrDefault(emptyList()) }
        return (own + linked).sortedWith(compareBy({ it.folder ?: "" }, { it.name.lowercase() }))
    }

    fun folderInfo(uri: String): LinkedFolder {
        val tree = Uri.parse(uri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = runCatching {
            context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: tree.lastPathSegment ?: "Folder"
        return LinkedFolder(uri, name)
    }

    /** Keeps read access to a folder the user picked (survives restarts). */
    fun linkFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun unlinkFolder(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Lists .gguf files in a linked folder (and its sub-folders, two levels deep). */
    private fun scanFolder(tree: Uri): List<LocalModel> {
        val folderName = folderInfo(tree.toString()).name
        val out = mutableListOf<LocalModel>()
        fun walk(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 2) walk(id, depth + 1)
                    } else if (name.endsWith(".gguf", ignoreCase = true)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out += LocalModel(uri.toString(), name, c.getLong(2), isProjectorName(name), folderName, tree.toString())
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    fun displayName(ref: String): String =
        if (ref.startsWith("content://")) {
            runCatching {
                context.contentResolver.query(Uri.parse(ref), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "model.gguf"
        } else File(ref).name

    fun exists(ref: String): Boolean =
        if (ref.startsWith("content://")) {
            runCatching { context.contentResolver.openFileDescriptor(Uri.parse(ref), "r")?.close(); true }.getOrDefault(false)
        } else File(ref).exists()

    /**
     * The mmproj loader needs a path. For a linked-folder file this copies just the projector
     * (much smaller than the model) into app storage, once.
     */
    suspend fun cachedProjector(ref: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref)
        val name = displayName(ref)
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) size = c.getLong(0)
        }
        val dir = File(context.noBackupFilesDir, "projector-cache").apply { mkdirs() }
        val target = File(dir, name)
        if (target.exists() && target.length() == size) return@withContext target.absolutePath
        dir.listFiles()?.forEach { it.delete() } // keep only one cached projector
        val part = File(dir, "$name.part")
        context.contentResolver.openInputStream(uri)!!.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
        check(part.renameTo(target)) { "Could not cache $name" }
        target.absolutePath
    }

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

    // ------------------------------------------------------------------ image models

    fun imageDir(preset: ImageModelPreset) = File(modelsDir, "image/" + preset.id)

    fun imageFiles(preset: ImageModelPreset): Pair<File, File?> =
        File(imageDir(preset), preset.file) to preset.taesd?.let { File(File(modelsDir, "image/taesd"), it.file) }

    fun isDownloaded(preset: ImageModelPreset): Boolean {
        val (m, t) = imageFiles(preset)
        return m.exists() && (t == null || t.exists())
    }

    /** Downloads an image model (and the small TAESD decoder it uses, if any). Resumable. */
    suspend fun download(preset: ImageModelPreset, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val (model, taesd) = imageFiles(preset)
        val parts = listOfNotNull(preset.taesd?.let { it.url to taesd!! }, preset.url to model)
        val total = preset.sizeBytes + (preset.taesd?.sizeBytes ?: 0L)
        var doneBefore = 0L
        for ((url, target) in parts) {
            target.parentFile?.mkdirs()
            if (target.exists()) { doneBefore += target.length(); continue }
            val base = doneBefore
            downloadFile(url, target) { bytes -> onProgress(((base + bytes).toFloat() / total).coerceIn(0f, 1f)) }
            doneBefore += target.length()
        }
        onProgress(1f)
    }

    fun deleteImageModel(preset: ImageModelPreset) { imageDir(preset).deleteRecursively() }

    /**
     * Copies an image-model file the user downloaded themselves into place. It must be one of the
     * presets' files (same file name), or the TAESD decoder. @return what was imported
     */
    suspend fun importImageFile(uri: Uri, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        var name = ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: ""; size = c.getLong(1) }
        }
        val preset = IMAGE_PRESETS.firstOrNull { it.file.equals(name, ignoreCase = true) }
        val isTaesd = name.equals("taesd.safetensors", true) || name.equals("diffusion_pytorch_model.safetensors", true)
        val target = when {
            preset != null -> imageFiles(preset).first
            isTaesd -> imageFiles(IMAGE_PRESETS.first { it.taesd != null }).second!!
            else -> error("Not a supported image model file: $name. Expected one of: " +
                (IMAGE_PRESETS.map { it.file } + "taesd / diffusion_pytorch_model.safetensors").joinToString())
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var last = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (size > 0 && total - last > (1 shl 20)) { onProgress(total.toFloat() / size); last = total }
                }
            }
        }
        target.delete()
        check(part.renameTo(target)) { "Could not save $name" }
        preset?.title ?: "TAESD decoder"
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
            if (code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0) {
                // the .part is already complete (e.g. killed before the rename): just keep it
                val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (total != existing) {
                    part.delete()
                    error("Partial download of ${target.name} was invalid, tap Retry to start over")
                }
            } else {
                if (code == HttpURLConnection.HTTP_OK) existing = 0L // server ignored the range
                else if (code != HttpURLConnection.HTTP_PARTIAL) error("HTTP $code while downloading ${target.name}")
                copyResponse(conn, part, existing, onBytes)
            }
        } finally {
            conn.disconnect()
        }
        check(part.renameTo(target)) { "Could not save ${target.name}" }
    }

    private suspend fun copyResponse(conn: HttpURLConnection, part: File, existing: Long, onBytes: (Long) -> Unit) {
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
                var lastReport = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    // throttled like downloads: a UI state write per 64 KB chunk is wasted work
                    if (size > 0 && total - lastReport > (1 shl 20)) {
                        onProgress(total.toFloat() / size); lastReport = total
                    }
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
