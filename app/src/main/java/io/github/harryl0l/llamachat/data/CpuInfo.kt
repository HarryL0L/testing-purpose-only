package io.github.harryl0l.llamachat.data

import java.io.File

/**
 * The phone's CPU layout from the max frequency of each core. Phones mix fast and slow cores
 * (e.g. Dimensity 1200: 1×3.0 + 3×2.6 GHz Cortex-A78 and 4×2.0 GHz Cortex-A55). ggml splits work
 * evenly across threads and waits for all of them, so threads on slow cores hold the rest back:
 * using only the fast cores is usually fastest.
 */
object CpuInfo {
    data class Cluster(val maxMhz: Int, val cores: Int)

    val totalCores: Int = Runtime.getRuntime().availableProcessors()

    val clusters: List<Cluster> by lazy {
        val freqs = (0 until totalCores).mapNotNull { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
            }.getOrNull()
        }
        freqs.groupingBy { it }.eachCount().map { (mhz, n) -> Cluster(mhz, n) }.sortedByDescending { it.maxMhz }
    }

    /** Cores within 80% of the fastest core's frequency (big + mid cores, not the little ones). */
    val fastCores: Int by lazy {
        val top = clusters.firstOrNull()?.maxMhz ?: 0
        if (top == 0) (totalCores / 2).coerceAtLeast(1)
        else clusters.filter { it.maxMhz >= top * 0.8 }.sumOf { it.cores }.coerceIn(1, totalCores)
    }

    /** "8 cores: 1×3.0 + 3×2.6 + 4×2.0 GHz" */
    fun describe(): String =
        if (clusters.isEmpty()) "$totalCores cores"
        else "$totalCores cores: " + clusters.joinToString(" + ") { "${it.cores}×%.1f".format(it.maxMhz / 1000f) } + " GHz"
}
