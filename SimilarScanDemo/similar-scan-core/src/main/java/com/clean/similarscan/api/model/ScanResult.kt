package com.clean.similarscan.api.model

/**
 * SDK 对外扫描结果摘要。
 */
data class ScanResult(
    val assetCount: Int,
    val groups: List<SimilarGroup>,
    val message: String,
    /** 本次扫描总耗时，单位毫秒。 */
    val elapsedTimeMs: Long = 0L,
    /** 本次扫描总耗时的可读文本，例如 42s、3m 08s。 */
    val elapsedTimeText: String = "0s"
)
