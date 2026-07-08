package com.clean.similarscan.internal.model

/**
 * 后台扫描进度。processedCount 会随着批处理持续增长；discoveredGroupCount 表示当前已发布
 * 到 group 表的分组数，不代表 candidate edge 的实时数量。
 */
data class ScanProgress(
    val stage: ScanStage,
    val processedCount: Int,
    val discoveredGroupCount: Int,
    val message: String,
    /** true 表示本次进度已发布新的分组结果，UI 可以刷新首页结果列表。 */
    val resultUpdated: Boolean = false,
    /** 当前扫描已经运行的毫秒数，便于 UI 或测试工具做统计。 */
    val elapsedTimeMs: Long = 0L,
    /** 当前扫描耗时的可读文本，例如 42s、3m 08s。 */
    val elapsedTimeText: String = "0s"
)
