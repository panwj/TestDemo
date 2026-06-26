package com.clean.similarscan.api.model

/**
 * SDK 对外扫描进度。
 */
data class ScanProgress(
    val stage: ScanStage,
    val processedCount: Int,
    val discoveredGroupCount: Int,
    val message: String
)
