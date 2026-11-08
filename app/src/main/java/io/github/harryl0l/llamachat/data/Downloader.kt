package io.github.harryl0l.llamachat.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Resumable, verified HTTP downloads (no Android dependencies, unit tested on the JVM). */
class Downloader(private val userAgent: String = "LlamaChat-Android") {
    /**
     * Downloads [url] to [target] so that it can't end up corrupted:
     * - data goes to target.part and resumes with a Range request; If-Range with the server's ETag
     *   makes the server send the whole file again if it changed in between (URLs are also pinned
     *   to a revision), and a resume must start exactly where the .part ends
     * - the file only counts as complete at exactly [expectedSize] bytes; a short one stays a .part
     * - then its SHA-256 is checked; on a mismatch the .part is deleted (start over on Retry)
     * - it is flushed to storage (fsync) before the atomic rename to [target]
     */
    suspend fun download(
        url: String, target: File, expectedSize: Long, sha256: String,
        onBytes: (Long) -> Unit, onVerify: (Float) -> Unit,
    ) {
        val part = File(target.path + ".part")
        val etagFile = File(target.path + ".part.etag")
        var existing = if (part.exists()) part.length() else 0L
        if (existing > expectedSize) { part.delete(); etagFile.delete(); existing = 0L }

        if (existing < expectedSize) {
            val etag = etagFile.takeIf { it.exists() && existing > 0 }?.readText()?.trim()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "identity") // byte offsets must be file offsets
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=$existing-")
                    if (!etag.isNullOrEmpty()) setRequestProperty("If-Range", etag)
                }
            }
            try {
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> existing = 0L // fresh start (no range support, or the file changed)
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val start = conn.getHeaderField("Content-Range")
                            ?.substringAfter("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != existing) {
                            part.delete(); etagFile.delete()
                            error("The server resumed ${target.name} at the wrong place. Tap Retry to download it again.")
                        }
                    }
                    HTTP_RANGE_NOT_SATISFIABLE -> {} // nothing left to send: checked below
                    else -> error("HTTP $code while downloading ${target.name}")
                }
                if (conn.responseCode != HTTP_RANGE_NOT_SATISFIABLE) {
                    if (existing == 0L) {
                        val newTag = conn.getHeaderField("ETag")
                        // weak ETags can't be used with If-Range
                        if (newTag != null && !newTag.startsWith("W/")) etagFile.writeText(newTag) else etagFile.delete()
                    }
                    copyResponse(conn, part, existing, onBytes)
                }
            } finally {
                conn.disconnect()
            }
        }

        val got = part.length()
        if (got < expectedSize) {
            error("Download of ${target.name} stopped early (${got * 100 / expectedSize}%). Tap Retry to continue.")
        }
        if (got > expectedSize) {
            part.delete(); etagFile.delete()
            error("${target.name} came out larger than expected. Tap Retry to download it again.")
        }
        if (sha256.isNotEmpty() && sha256Of(part, onVerify) != sha256) {
            part.delete(); etagFile.delete()
            error("${target.name} was corrupted (checksum mismatch) and was deleted. Tap Retry to download it again.")
        }
        check(part.renameTo(target)) { "Could not save ${target.name}" }
        etagFile.delete()
        markerOf(target).writeText(sha256)
    }

    companion object {
        fun markerOf(file: File) = File(file.path + ".sha256")
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        suspend fun sha256Of(file: File, onProgress: (Float) -> Unit = {}): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val size = file.length().coerceAtLeast(1)
            var done = 0L
            var last = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    if (done - last > (16 shl 20)) { onProgress(done.toFloat() / size); last = done }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun copyResponse(conn: HttpURLConnection, part: File, existing: Long, onBytes: (Long) -> Unit) {
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, existing > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                var total = existing
                var lastReport = 0L
                try {
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
                } finally {
                    // whatever was written is a valid prefix; make sure it is really on storage
                    runCatching { out.flush(); out.fd.sync() }
                }
            }
        }
    }

}
