package com.clean.similarscan.api.model

/**
 * SDK 对外扫描结果摘要。
 */
data class ScanResult(
    val assetCount: Int,
    val groups: List<SimilarGroup>,
    val message: String
)
