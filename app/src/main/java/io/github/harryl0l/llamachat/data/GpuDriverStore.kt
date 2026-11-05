package io.github.harryl0l.llamachat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A custom Vulkan driver package for libadrenotools (experimental build), e.g. Mesa Turnip for
 * Adreno GPUs. Packages are the usual AdrenoTools .zip format: a driver .so plus a meta.json
 * whose "libraryName" names it.
 */
data class GpuDriver(
    val id: String,          // folder name under files/gpu_drivers
    val name: String,
    val description: String,
    val library: String,     // soname of the driver library inside the folder
    val dir: File,
)

class GpuDriverStore(private val context: Context) {
    // must be in app-private storage: libadrenotools can't load drivers from shared storage
    private val root = File(context.filesDir, "gpu_drivers").apply { mkdirs() }

    suspend fun list(): List<GpuDriver> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read).sortedBy { it.name.lowercase() }
    }

    suspend fun get(id: String): GpuDriver? = withContext(Dispatchers.IO) { read(File(root, id)) }

    private fun read(dir: File): GpuDriver? {
        if (!dir.isDirectory) return null
        val meta = File(dir, "meta.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val library = meta?.optString("libraryName")?.takeIf { it.isNotBlank() && File(dir, it).exists() }
            ?: dir.listFiles().orEmpty().firstOrNull { it.name.endsWith(".so") }?.name
            ?: return null
        val version = meta?.optString("driverVersion").orEmpty()
        return GpuDriver(
            id = dir.name,
            name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: dir.name,
            description = listOf(meta?.optString("description").orEmpty(), version).filter { it.isNotBlank() }.joinToString(" · "),
            library = library,
            dir = dir,
        )
    }

    /** Extracts a driver .zip into app storage and returns it. */
    suspend fun import(uri: Uri): GpuDriver = withContext(Dispatchers.IO) {
        var zipName = "driver"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { zipName = it.substringBeforeLast('.') }
        }
        val id = zipName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "driver" }
        val tmp = File(root, ".$id.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            val tmpPath = tmp.canonicalPath + File.separator
            ZipInputStream(context.contentResolver.openInputStream(uri)!!.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // flatten folders and refuse paths that escape the target ("zip slip")
                    val out = File(tmp, File(e.name).name)
                    if (e.isDirectory || !out.canonicalPath.startsWith(tmpPath)) continue
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
            val driver = read(tmp) ?: error("No driver library (.so) found in that zip")
            val target = File(root, id)
            target.deleteRecursively()
            check(tmp.renameTo(target)) { "Could not save the driver" }
            read(target)!!.also { check(driver.library == it.library) }
        } finally {
            tmp.deleteRecursively()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { File(root, id).deleteRecursively() }
}
