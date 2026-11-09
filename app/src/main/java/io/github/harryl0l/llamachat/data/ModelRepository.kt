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
    /** repository commit the files are pinned to, so they can't change between download attempts */
    val revision: String,
    val modelFile: String,
    val mmprojFile: String,
    val modelSize: Long,
    val mmprojSize: Long,
    /** SHA-256 of the files (Hugging Face LFS ids), checked after every download */
    val modelSha256: String,
    val mmprojSha256: String,
) {
    val sizeBytes: Long get() = modelSize + mmprojSize
    fun url(file: String) = "https://huggingface.co/$repo/resolve/$revision/$file?download=true"
}

val MODEL_PRESETS = listOf(
    ModelPreset(
        "smolvlm2-500m", "SmolVLM2 500M Video",
        "Tiny and fast. Trained for video. Good first try on any phone.",
        "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF", "ccd7aae53bcb1997355c2f094959e72b3642ce17",
        "SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
        436_808_704L, 108_785_184L,
        "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc",
        "921dc7e259f308e5b027111fa185efcbf33db13f6e35749ddf7f5cdb60ef520b",
    ),
    ModelPreset(
        "qwen3-vl-2b", "Qwen3-VL 2B",
        "Strong image & video understanding with timestamps. Needs ~4 GB RAM.",
        "ggml-org/Qwen3-VL-2B-Instruct-GGUF", "ea6a11058182570be6436b9a2e4ee7f7b49f908d",
        "Qwen3-VL-2B-Instruct-Q8_0.gguf", "mmproj-Qwen3-VL-2B-Instruct-Q8_0.gguf",
        1_834_427_296L, 445_053_056L,
        "b7802e29f71a9e5b5e3f83f613df898a2204342dcea71a231ea501d481813c39",
        "69066c8f279ec85ff48ab4059f6ebba0d2932ca57667f2bbdac7d9805bca9e7b",
    ),
    ModelPreset(
        "qwen2.5-vl-3b", "Qwen2.5-VL 3B",
        "Great at documents, charts and OCR. Needs ~5 GB RAM.",
        "ggml-org/Qwen2.5-VL-3B-Instruct-GGUF", "5037fcf163dd95d1e41d1974465f0898ed108ca2",
        "Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", "mmproj-Qwen2.5-VL-3B-Instruct-Q8_0.gguf",
        1_929_901_056L, 844_757_728L,
        "d02fe9b69ad8cadbbd228e387667af66612c44bed29ffc8eb1e7caf9ac486c12",
        "980c9b2f78c04e6cff93d277ada09e768394f112d75db3b4e9dea8a69f9fb904",
    ),
    ModelPreset(
        "gemma-3-4b", "Gemma 3 4B",
        "Best general chat quality of the bunch. Needs ~6 GB RAM.",
        "ggml-org/gemma-3-4b-it-GGUF", "d0976223747697cb51e056d85c532013931fe52e",
        "gemma-3-4b-it-Q4_K_M.gguf", "mmproj-model-f16.gguf",
        2_489_757_856L, 851_251_104L,
        "882e8d2db44dc554fb0ea5077cb7e4bc49e7342a1f0da57901c0802ea21a0863",
        "8c0fb064b019a6972856aaae2c7e4792858af3ca4561be2dbf649123ba6c40cb",
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
    val sha256: String = "",
)

data class TaesdFile(val url: String, val file: String, val sizeBytes: Long, val sha256: String)

private val TAESD = TaesdFile(
    "https://huggingface.co/madebyollin/taesd/resolve/614f76814bbe30edbe2e627ace1c2234c81a2c0e/diffusion_pytorch_model.safetensors?download=true",
    "taesd.safetensors", 9_793_292L, "db169d69145ec4ff064e49d99c95fa05d3eb04ee453de35824a6d0f325513549",
)

/** TAESD for SDXL models (different latent space; the SD 1.x/2.x TAESD gives garbage with SDXL) */
private val TAESDXL = TaesdFile(
    "https://huggingface.co/madebyollin/taesdxl/resolve/b20258aaef75ef61e659c1e0f14f251cf0ad153e/diffusion_pytorch_model.safetensors?download=true",
    "taesdxl.safetensors", 9_793_292L, "ff4824aca94dd6111e0340fa749347fb74101060d9712cb5ef1ca8f1cf17502f",
)

val IMAGE_PRESETS = listOf(
    ImageModelPreset(
        "sdxs-512", "SDXS-512",
        "Tiny one-step model. Fastest (about 6 s on a desktop CPU, several times that on a phone); simpler images.",
        "https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/3144d898d61492f8382ffcabec055733fc5b2a0e/sdxs-512-tinySDdistilled_Q8_0.gguf?download=true",
        "sdxs-512-tinySDdistilled_Q8_0.gguf", 682_847_200L,
        defaultSteps = 1, maxSteps = 2, cfgScale = 1.0f,
        sha256 = "409ab23582ee074c6b9d5395784fc0741b0599fb9d138686c69087c71678eb6a",
    ),
    ImageModelPreset(
        "sd-turbo", "SD-Turbo",
        "Better images in 1–4 steps (about 15 s for 2 steps on a desktop CPU). Uses the TAESD fast decoder.",
        "https://huggingface.co/Green-Sky/SD-Turbo-GGUF/resolve/19a31586d02d64a73b4419bc193b3ecfaf38e1f0/sd_turbo-f16-q8_0.gguf?download=true",
        "sd_turbo-f16-q8_0.gguf", 2_023_745_376L,
        defaultSteps = 2, maxSteps = 4, cfgScale = 1.0f, taesd = TAESD,
        sha256 = "d50be7655f0a554cf8041c145d88b210bd5f3c545423119dee62ae08cae51580",
    ),
    ImageModelPreset(
        "sdxl-turbo", "SDXL-Turbo",
        "SD-Turbo's big brother: better anatomy, detail and prompt following, about 3× slower. Needs ~4.5 GB RAM " +
            "(unload the chat model first). Uses the TAESDXL fast decoder. Non-commercial license.",
        "https://huggingface.co/OlegSkutte/sdxl-turbo-GGUF/resolve/62a28b8b2c3cb4bf76d9aad45a5b798047dc786c/sd_xl_turbo_1.0.q8_0.gguf?download=true",
        "sd_xl_turbo_1.0.q8_0.gguf", 4_098_988_672L,
        defaultSteps = 2, maxSteps = 4, cfgScale = 1.0f, taesd = TAESDXL,
        sha256 = "f5659e6cdad699b1432870cc9fe811359e45c8a331687be505d0ba05c8364720",
    ),
)

/** The fast decoders the app knows, by the file name they're stored under. */
val TAESD_FILES = listOf(TAESD, TAESDXL)

val CUSTOM_IMAGE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt")

/**
 * An image model the user imported. Sampling defaults are guessed from the file name:
 * LCM models need the LCM sampler and few steps; Turbo/Lightning models few steps without CFG.
 */
class CustomImageModel(val file: File) {
    val id: String get() = "custom:" + file.name
    val title: String get() = file.nameWithoutExtension
    private val lower = file.name.lowercase()
    val isLcm: Boolean get() = "lcm" in lower
    val isFewStep: Boolean get() = isLcm || "turbo" in lower || "lightning" in lower || "hyper" in lower
    /** SDXL-type models need a different tiny decoder (TAESDXL), so TAESD is only used for SD 1.x/2.x */
    val isXl: Boolean get() = "xl" in lower || "pony" in lower || "illustrious" in lower
    val defaultSteps: Int get() = when { isLcm -> 6; isFewStep -> 4; else -> 20 }
    val maxSteps: Int get() = if (isFewStep) 12 else 50
    // CFG 1 skips the second (negative-prompt) pass: half the time, nearly the same images for LCM/turbo
    val defaultCfg: Float get() = if (isFewStep) 1.0f else 7.0f
    val defaultSampler: Int get() = if (isLcm) SAMPLER_LCM else SAMPLER_EULER_A
}

// stable-diffusion.cpp sample_method_t values
const val SAMPLER_EULER_A = 1
const val SAMPLER_DPMPP2M = 5
const val SAMPLER_LCM = 9
val SAMPLERS = listOf(SAMPLER_EULER_A to "Euler A", SAMPLER_LCM to "LCM", SAMPLER_DPMPP2M to "DPM++ 2M")

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

/** Known image-creation model files (stable-diffusion.cpp), which llama.cpp can't load. */
private fun isImageModelName(name: String) = IMAGE_PRESETS.any { it.file.equals(name, ignoreCase = true) }


class ModelRepository(private val context: Context) {
    val modelsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun presetDir(preset: ModelPreset) = File(modelsDir, preset.id)

    fun isDownloaded(preset: ModelPreset): Boolean =
        isVerified(File(presetDir(preset), preset.modelFile), preset.modelSize, preset.modelSha256) &&
            isVerified(File(presetDir(preset), preset.mmprojFile), preset.mmprojSize, preset.mmprojSha256)

    /**
     * A file counts as downloaded only with the right size *and* a marker saying its checksum was
     * verified. Files from older app versions (no marker) get checked again on the next Download
     * tap: kept if intact, downloaded again if corrupted.
     */
    private fun isVerified(file: File, size: Long, sha256: String): Boolean =
        file.length() == size && (sha256.isEmpty() || runCatching { markerOf(file).readText().trim() == sha256 }.getOrDefault(false))

    private fun markerOf(file: File) = Downloader.markerOf(file)
    private val downloader = Downloader()
    private suspend fun downloadFile(
        url: String, target: File, expectedSize: Long, sha256: String,
        onBytes: (Long) -> Unit, onVerify: (Float) -> Unit,
    ) = downloader.download(url, target, expectedSize, sha256, onBytes, onVerify)
    private suspend fun sha256Of(file: File, onProgress: (Float) -> Unit) = Downloader.sha256Of(file, onProgress)

    fun presetFiles(preset: ModelPreset): Pair<File, File> =
        File(presetDir(preset), preset.modelFile) to File(presetDir(preset), preset.mmprojFile)

    /** GGUFs in app storage plus those found in the linked folders. */
    fun localModels(folders: Set<String>): List<LocalModel> {
        // image-creation models live under models/image and are used by "Create image", not by
        // llama.cpp: never list them as chat models (loading one fails with "not a valid GGUF")
        val imageRoot = File(modelsDir, "image")
        val own = modelsDir.walkTopDown()
            .onEnter { it != imageRoot }
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) && !isImageModelName(it.name) }
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
                    } else if (name.endsWith(".gguf", ignoreCase = true) && !isImageModelName(name)) {
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
    /** Downloads both files of a preset (resumable, verified). [onProgress]: 0..1, verifying. */
    suspend fun download(preset: ModelPreset, onProgress: (Float, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        val dir = presetDir(preset).apply { mkdirs() }
        downloadAll(
            listOf(
                RemoteFile(preset.url(preset.mmprojFile), File(dir, preset.mmprojFile), preset.mmprojSize, preset.mmprojSha256),
                RemoteFile(preset.url(preset.modelFile), File(dir, preset.modelFile), preset.modelSize, preset.modelSha256),
            ),
            onProgress,
        )
    }

    /** A file to download and exactly what it must be once complete. */
    private class RemoteFile(val url: String, val target: File, val size: Long, val sha256: String)

    private suspend fun downloadAll(files: List<RemoteFile>, onProgress: (Float, Boolean) -> Unit) {
        val total = files.sumOf { it.size }.coerceAtLeast(1)
        var doneBefore = 0L
        for (f in files) {
            f.target.parentFile?.mkdirs()
            if (!isVerified(f.target, f.size, f.sha256) && f.target.length() == f.size) {
                // complete but never verified (older app version): check it instead of downloading again
                if (f.sha256.isEmpty() || sha256Of(f.target) { p -> onProgress(p, true) } == f.sha256) {
                    markerOf(f.target).writeText(f.sha256)
                } else {
                    f.target.delete()
                    markerOf(f.target).delete()
                }
            }
            if (!isVerified(f.target, f.size, f.sha256)) {
                f.target.delete()
                val base = doneBefore
                downloadFile(f.url, f.target, f.size, f.sha256,
                    onBytes = { bytes -> onProgress(((base + bytes).toFloat() / total).coerceIn(0f, 1f), false) },
                    onVerify = { p -> onProgress(p, true) })
            }
            doneBefore += f.size
        }
        onProgress(1f, false)
    }

    // ------------------------------------------------------------------ image models

    fun imageDir(preset: ImageModelPreset) = File(modelsDir, "image/" + preset.id)

    fun imageFiles(preset: ImageModelPreset): Pair<File, File?> =
        File(imageDir(preset), preset.file) to preset.taesd?.let { File(taesdDir(), it.file) }

    fun isDownloaded(preset: ImageModelPreset): Boolean {
        val (m, t) = imageFiles(preset)
        return isVerified(m, preset.sizeBytes, preset.sha256) &&
            (t == null || isVerified(t, preset.taesd!!.sizeBytes, preset.taesd.sha256))
    }

    /** Downloads an image model (and the small TAESD decoder it uses, if any). Resumable. */
    suspend fun download(preset: ImageModelPreset, onProgress: (Float, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        val (model, taesd) = imageFiles(preset)
        downloadAll(
            listOfNotNull(
                preset.taesd?.let { RemoteFile(it.url, taesd!!, it.sizeBytes, it.sha256) },
                RemoteFile(preset.url, model, preset.sizeBytes, preset.sha256),
            ),
            onProgress,
        )
    }

    fun deleteImageModel(preset: ImageModelPreset) { imageDir(preset).deleteRecursively() }

    /** Image models the user imported themselves (any stable-diffusion.cpp-supported file). */
    val customImageDir: File get() = File(modelsDir, "image/custom").apply { mkdirs() }

    fun customImageModels(): List<CustomImageModel> =
        customImageDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in CUSTOM_IMAGE_EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .map { CustomImageModel(it) }

    fun taesdDir() = File(modelsDir, "image/taesd")

    /** Fast decoder for SD 1.x/2.x ([xl] false) or SDXL ([xl] true) models, if present (downloaded with a preset or imported). */
    fun taesdFile(xl: Boolean): File? =
        File(taesdDir(), (if (xl) TAESDXL else TAESD).file).takeIf { isVerified(it, TAESD.sizeBytes, (if (xl) TAESDXL else TAESD).sha256) }

    /**
     * Copies an image-model file the user downloaded themselves into place. It must be one of the
     * presets' files (same file name), a TAESD / TAESDXL decoder, or the user's own model. @return what was imported
     */
    suspend fun importImageFile(uri: Uri, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        var name = ""
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: ""; size = c.getLong(1) }
        }
        val preset = IMAGE_PRESETS.firstOrNull { it.file.equals(name, ignoreCase = true) }
        // taesd.safetensors / taesdxl.safetensors, or the upstream name (which one is decided by checksum)
        val isTaesd = TAESD_FILES.any { it.file.equals(name, true) } || name.equals("diffusion_pytorch_model.safetensors", true)
        val ext = name.substringAfterLast('.', "").lowercase()
        var target = when {
            preset != null -> imageFiles(preset).first
            isTaesd -> File(taesdDir(), TAESD_FILES.firstOrNull { it.file.equals(name, true) }?.file ?: name)
            ext in CUSTOM_IMAGE_EXTENSIONS -> File(customImageDir, name) // your own model (e.g. a fine-tune)
            else -> error("$name isn't an image model file (.gguf, .safetensors or .ckpt).")
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            java.io.FileOutputStream(part).use { out ->
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
                out.flush()
                out.fd.sync()
            }
        }
        checkImportedCopy(part, size, name)
        if (preset == null && !isTaesd) {
            // own model: no known checksum; complete copy + header checked above
            target.delete()
            check(part.renameTo(target)) { "Could not save $name" }
            return@withContext name
        }
        val sha = sha256Of(part) { onProgress(it) }
        val taesdMatch = if (isTaesd) TAESD_FILES.firstOrNull { it.sha256 == sha } else null
        val expectedSha = preset?.sha256 ?: taesdMatch?.sha256
        if (expectedSha == null || sha != expectedSha) {
            part.delete()
            error("$name doesn't match the expected file (checksum mismatch): it's corrupted or a different version.")
        }
        if (taesdMatch != null) target = File(taesdDir(), taesdMatch.file)
        target.delete()
        check(part.renameTo(target)) { "Could not save $name" }
        markerOf(target).writeText(expectedSha)
        preset?.title ?: if (taesdMatch == TAESDXL) "TAESDXL decoder" else "TAESD decoder"
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
            java.io.FileOutputStream(part).use { out ->
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
                out.flush()
                out.fd.sync()
            }
        }
        checkImportedCopy(part, size, name)
        target.delete()
        check(part.renameTo(target)) { "Could not save $name" }
        target
    }

    /** An imported copy must be complete and, for GGUF, start with the GGUF magic. */
    private fun checkImportedCopy(part: File, expectedSize: Long, name: String) {
        if (expectedSize > 0 && part.length() != expectedSize) {
            part.delete()
            error("Copying $name stopped early (${part.length()} of $expectedSize bytes). Try again.")
        }
        if (name.endsWith(".gguf", ignoreCase = true)) {
            val magic = ByteArray(4)
            val n = part.inputStream().use { it.read(magic) }
            if (n != 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                part.delete()
                error("$name isn't a valid GGUF file (it may be incomplete or corrupted).")
            }
        }
    }

    fun delete(file: File) {
        file.delete()
        markerOf(file).delete()
        file.parentFile?.takeIf { it != modelsDir && it.list().isNullOrEmpty() }?.delete()
    }
}
