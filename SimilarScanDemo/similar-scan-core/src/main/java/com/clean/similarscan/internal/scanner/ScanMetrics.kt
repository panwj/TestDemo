package com.clean.similarscan.internal.scanner

import android.util.Log

/**
 * 扫描性能指标收集器。
 *
 * 该对象会被扫描线程和计算线程同时调用，因此内部写入需要同步。工作线程耗时是累加值，
 * 不等同于真实墙钟时间；真实耗时以 logSummary 的 elapsed 为准。
 */
internal class ScanMetrics {
    private val totals = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Int>()

    fun <T> measure(name: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        return try {
            block()
        } finally {
            add(name, (System.nanoTime() - startedAt) / 1_000_000L)
        }
    }

    fun logSummary(
        modeName: String,
        visited: Int,
        fingerprinted: Int,
        skippedUnchanged: Int,
        elapsed: Long,
        elapsedText: String
    ) {
        val totalsSnapshot: Map<String, Long>
        val countsSnapshot: Map<String, Int>
        synchronized(this) {
            totalsSnapshot = totals.toMap()
            countsSnapshot = counts.toMap()
        }
        Log.d(
            TAG,
            "scan=$modeName elapsed=${elapsed}ms elapsedText=$elapsedText visited=$visited fingerprinted=$fingerprinted reused=$skippedUnchanged"
        )
        totalsSnapshot.forEach { (name, duration) ->
            Log.d(TAG, "metric.$name=${duration}ms")
        }
        countsSnapshot.forEach { (name, count) ->
            Log.d(TAG, "count.$name=$count")
        }
    }

    private fun add(name: String, durationMs: Long) {
        synchronized(this) {
            totals[name] = (totals[name] ?: 0L) + durationMs
        }
    }

    fun increment(name: String) {
        synchronized(this) {
            counts[name] = (counts[name] ?: 0) + 1
        }
    }

    fun addCount(name: String, count: Int) {
        if (count <= 0) return
        synchronized(this) {
            counts[name] = (counts[name] ?: 0) + count
        }
    }

    private companion object {
        private const val TAG = "SimilarScanMetrics"
    }
}
