package io.github.harryl0l.llamachat.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The downloader against a local server that misbehaves on purpose: dropped connections, ignored
 * ranges, a file that changes between attempts, wrong resume offsets, corrupted bytes.
 */
class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val versionA = Random(1).nextBytes(3_000_000)
    private val versionB = Random(2).nextBytes(3_000_000)

    // server behaviour, changed per test
    @Volatile private var content = versionA
    @Volatile private var etag = "\"a\""
    @Volatile private var dropAfter = -1L          // close the connection after this many body bytes
    @Volatile private var dropWithoutError = false // ...and pretend the body ended there (no Content-Length)
    @Volatile private var ignoreRange = false
    @Volatile private var wrongRangeStart = false
    @Volatile private var corruptByte = -1
    private val rangeRequests = mutableListOf<String?>()
    private val encodings = mutableListOf<String?>()

    private lateinit var server: HttpServer
    private val url get() = "http://127.0.0.1:${server.address.port}/model.gguf"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.gguf") { ex -> serve(ex) }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun serve(ex: HttpExchange) {
        val data = content.copyOf().also { if (corruptByte >= 0) it[corruptByte] = (it[corruptByte] + 1).toByte() }
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        synchronized(rangeRequests) { rangeRequests += range; encodings += ex.requestHeaders.getFirst("Accept-Encoding") }
        ex.responseHeaders.add("ETag", etag)
        var start = 0L
        var status = 200
        if (range != null && !ignoreRange && (ifRange == null || ifRange == etag)) {
            start = range.removePrefix("bytes=").substringBefore('-').toLong()
            if (start >= data.size) {
                ex.responseHeaders.add("Content-Range", "bytes */${data.size}")
                ex.sendResponseHeaders(416, -1); ex.close(); return
            }
            status = 206
            val shown = if (wrongRangeStart) start + 10 else start
            ex.responseHeaders.add("Content-Range", "bytes $shown-${data.size - 1}/${data.size}")
        }
        val body = data.copyOfRange(start.toInt(), data.size)
        val send = if (dropAfter in 0 until body.size) body.copyOf(dropAfter.toInt()) else body
        ex.sendResponseHeaders(status, if (dropWithoutError) 0 else body.size.toLong()) // 0 = chunked
        runCatching { ex.responseBody.write(send); ex.responseBody.flush() }
        ex.close()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun download(expected: ByteArray = content) = runBlocking {
        Downloader().download(url, tmp.root.resolve("model.gguf"), expected.size.toLong(), sha(expected), {}, {})
    }

    private val target get() = tmp.root.resolve("model.gguf")
    private val part get() = tmp.root.resolve("model.gguf.part")

    private fun assertDone(expected: ByteArray) {
        assertTrue("target exists", target.exists())
        assertTrue("content matches", target.readBytes().contentEquals(expected))
        assertFalse(".part removed", part.exists())
        assertEquals(sha(expected), Downloader.markerOf(target).readText())
    }

    private fun expectFailure(message: String, block: () -> Unit) {
        try { block(); fail("expected failure: $message") } catch (e: Exception) {
            assertTrue("'${e.message}' should mention '$message'", e.message.orEmpty().contains(message, ignoreCase = true))
        }
    }

    @Test fun plainDownload() {
        download()
        assertDone(versionA)
        assertEquals("identity", encodings.single()) // never a compressed transfer
    }

    @Test fun droppedConnectionResumesFromTheRightOffset() {
        dropAfter = 1_000_000
        runCatching { download() }
        assertFalse(target.exists())
        val have = part.length()
        assertTrue("kept a partial file", have in 1..2_999_999)
        dropAfter = -1
        download()
        assertDone(versionA)
        assertEquals("bytes=$have-", rangeRequests.last())
    }

    @Test fun silentEarlyEndIsNotTreatedAsComplete() {
        dropAfter = 2_000_000; dropWithoutError = true
        expectFailure("stopped early") { download() }
        assertFalse(target.exists())
        dropAfter = -1; dropWithoutError = false
        download()
        assertDone(versionA)
    }

    @Test fun serverIgnoringRangeStartsOver() {
        dropAfter = 1_000_000
        runCatching { download() }
        dropAfter = -1; ignoreRange = true
        download()
        assertDone(versionA)
    }

    @Test fun fileChangedOnServerBetweenAttemptsGivesTheNewFileWholeNotAMix() {
        dropAfter = 1_000_000
        runCatching { download() }
        dropAfter = -1
        content = versionB; etag = "\"b\"" // If-Range no longer matches: server sends all of B
        download(versionB)
        assertDone(versionB)
    }

    @Test fun wrongResumeOffsetIsRejected() {
        dropAfter = 1_000_000
        runCatching { download() }
        dropAfter = -1; wrongRangeStart = true
        expectFailure("wrong place") { download() }
        assertFalse(part.exists()) // thrown away, starts over
        wrongRangeStart = false
        download()
        assertDone(versionA)
    }

    @Test fun corruptedBytesAreCaughtByTheChecksum() {
        corruptByte = 1_234_567
        expectFailure("checksum") { runBlocking {
            Downloader().download(url, target, versionA.size.toLong(), sha(versionA), {}, {})
        } }
        assertFalse(target.exists())
        assertFalse(part.exists())
        corruptByte = -1
        download()
        assertDone(versionA)
    }

    @Test fun completePartFromAKilledAppIsFinishedWithoutRedownloading() {
        part.writeBytes(versionA) // app killed right before the rename
        download()
        assertDone(versionA)
    }
}
