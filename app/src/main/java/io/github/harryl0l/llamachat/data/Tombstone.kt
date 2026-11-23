package io.github.harryl0l.llamachat.data

/**
 * Reads the useful parts of an Android native crash report (the "tombstone" protobuf that
 * ApplicationExitInfo.traceInputStream returns for native crashes, Android 12+): the signal, the
 * abort message, the crashing thread's backtrace and the app's last log lines. The full report also
 * holds every other thread, which only hides the one that matters.
 *
 * Field numbers from AOSP system/core/debuggerd/proto/tombstone.proto.
 */
object Tombstone {
    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (pos < end) {
                val x = b[pos++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 63) break
            }
            return r
        }

        /** next field: (number, wire type); then call one of the value readers or skip() */
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun bytes(): Reader { val n = varint().toInt(); val r = Reader(b, pos, minOf(end, pos + n)); pos = minOf(end, pos + n); return r }
        fun string(): String { val r = bytes(); return String(b, r.pos, r.end - r.pos, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> pos = end // groups / unknown: give up on this message
            }
        }
    }

    private class Frame(val relPc: Long, val function: String, val offset: Long, val file: String)

    private fun frame(r: Reader): Frame {
        var relPc = 0L; var fn = ""; var off = 0L; var file = ""
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> relPc = r.varint()
                f == 4 && w == 2 -> fn = r.string()
                f == 5 && w == 0 -> off = r.varint()
                f == 6 && w == 2 -> file = r.string()
                else -> r.skip(w)
            }
        }
        return Frame(relPc, fn, off, file)
    }

    private class Thread(val id: Int, val name: String, val frames: List<Frame>)

    private fun thread(r: Reader): Thread {
        var id = 0; var name = ""; val frames = mutableListOf<Frame>()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 0 -> id = r.varint().toInt()
                f == 2 && w == 2 -> name = r.string()
                f == 4 && w == 2 -> frames += frame(r.bytes())
                else -> r.skip(w)
            }
        }
        return Thread(id, name, frames)
    }

    /**
     * A readable summary, or null if [data] isn't a tombstone. [logLines]: how many of the app's
     * last log lines (logcat, captured in the report) to include.
     */
    fun summarize(data: ByteArray, logLines: Int = 25): String? = runCatching {
        val r = Reader(data)
        var tid = 0
        var signal = ""
        var abort = ""
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, Thread>()
        val logs = mutableListOf<String>()
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 6 && w == 0 -> tid = r.varint().toInt()
                f == 10 && w == 2 -> {
                    val s = r.bytes()
                    var name = ""; var codeName = ""; var hasAddr = false; var addr = 0L
                    while (s.more()) {
                        val (sf, sw) = s.tag()
                        when {
                            sf == 2 && sw == 2 -> name = s.string()
                            sf == 4 && sw == 2 -> codeName = s.string()
                            sf == 8 && sw == 0 -> hasAddr = s.varint() != 0L
                            sf == 9 && sw == 0 -> addr = s.varint()
                            else -> s.skip(sw)
                        }
                    }
                    signal = "$name ($codeName)" + if (hasAddr) ", fault address 0x" + java.lang.Long.toHexString(addr) else ""
                }
                f == 14 && w == 2 -> abort = r.string()
                f == 15 && w == 2 -> {
                    val c = r.bytes()
                    while (c.more()) {
                        val (cf, cw) = c.tag()
                        if (cf == 1 && cw == 2) causes += c.string() else c.skip(cw)
                    }
                }
                f == 16 && w == 2 -> { // map<uint32, Thread> entry
                    val e = r.bytes()
                    var key = 0; var t: Thread? = null
                    while (e.more()) {
                        val (ef, ew) = e.tag()
                        when {
                            ef == 1 && ew == 0 -> key = e.varint().toInt()
                            ef == 2 && ew == 2 -> t = thread(e.bytes())
                            else -> e.skip(ew)
                        }
                    }
                    t?.let { threads[if (key != 0) key else it.id] = it }
                }
                f == 18 && w == 2 -> { // LogBuffer { name = 1; repeated LogMessage logs = 2 }
                    val lb = r.bytes()
                    while (lb.more()) {
                        val (lf, lw) = lb.tag()
                        if (lf == 2 && lw == 2) {
                            val m = lb.bytes()
                            var prio = 0; var tag = ""; var msg = ""
                            while (m.more()) {
                                val (mf, mw) = m.tag()
                                when {
                                    mf == 4 && mw == 0 -> prio = m.varint().toInt()
                                    mf == 5 && mw == 2 -> tag = m.string()
                                    mf == 6 && mw == 2 -> msg = m.string()
                                    else -> m.skip(mw)
                                }
                            }
                            val p = "??VDIWEF".getOrElse(prio) { '?' }
                            logs += "$p $tag: ${msg.trimEnd()}"
                        } else lb.skip(lw)
                    }
                }
                else -> r.skip(w)
            }
        }
        if (signal.isEmpty() && threads.isEmpty()) return null
        buildString {
            append("Signal: ").append(signal.ifEmpty { "?" })
            if (abort.isNotBlank()) append("\nAbort message: ").append(abort.trim())
            causes.forEach { append("\nCause: ").append(it) }
            val t = threads[tid]
            if (t != null) {
                append("\nCrashed thread: ").append(t.name.ifEmpty { "#$tid" })
                t.frames.take(32).forEachIndexed { i, fr ->
                    append("\n#%02d %s".format(i, fr.file.substringAfterLast('/')))
                    if (fr.function.isNotEmpty()) append(" ").append(fr.function).append("+").append(fr.offset)
                    else append(" pc 0x").append(java.lang.Long.toHexString(fr.relPc))
                }
            }
            if (logs.isNotEmpty() && logLines > 0) {
                append("\nLast log lines:")
                logs.takeLast(logLines).forEach { append("\n").append(it.take(300)) }
            }
        }
    }.getOrNull()
}
