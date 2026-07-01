package com.clean.similarscan.internal.scanner

/**
 * 扫描耗时格式化工具。
 *
 * SDK 内部统一用毫秒做统计，对外展示时转成测试人员更容易阅读的分秒格式。
 */
internal object ScanElapsedFormatter {
    fun format(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return when {
            hours > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
            totalMinutes > 0L -> "${totalMinutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
    }
}
