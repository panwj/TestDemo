package com.example.similarscandemo.model

/**
 * 后台扫描进度。processedCount 会随着批处理持续增长，discoveredGroupCount 会随着匹配实时更新。
 */
data class ScanProgress(
    val stage: ScanStage,
    val processedCount: Int,
    val discoveredGroupCount: Int,
    val message: String
)
