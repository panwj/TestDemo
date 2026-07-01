package com.clean.similarscan.api.model

/**
 * SDK 对外扫描进度。
 */
data class ScanProgress(
    val stage: ScanStage,
    val processedCount: Int,
    val discoveredGroupCount: Int,
    val message: String,
    /** 当前扫描已经运行的毫秒数。 */
    val elapsedTimeMs: Long = 0L,
    /** 当前扫描耗时的可读文本，例如 42s、3m 08s。 */
    val elapsedTimeText: String = "0s"
)
