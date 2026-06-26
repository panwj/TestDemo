package com.clean.similarscan.internal.model

/**
 * 一次扫描的汇总结果。
 */
data class ScanResult(
    val assetCount: Int,
    val groups: List<SimilarGroup>,
    val message: String
)
