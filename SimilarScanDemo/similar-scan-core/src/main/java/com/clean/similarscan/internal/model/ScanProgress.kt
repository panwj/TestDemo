package com.clean.similarscan.internal.model

/**
 * 后台扫描进度。processedCount 会随着批处理持续增长，discoveredGroupCount 会随着匹配实时更新。
 */
data class ScanProgress(
    val stage: ScanStage,
    val processedCount: Int,
    val discoveredGroupCount: Int,
    val message: String,
    /** 当前扫描已经运行的毫秒数，便于 UI 或测试工具做统计。 */
    val elapsedTimeMs: Long = 0L,
    /** 当前扫描耗时的可读文本，例如 42s、3m 08s。 */
    val elapsedTimeText: String = "0s"
)
