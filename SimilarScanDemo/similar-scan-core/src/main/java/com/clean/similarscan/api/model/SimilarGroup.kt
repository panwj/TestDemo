package com.clean.similarscan.api.model

/**
 * SDK 对外相似/相同/其他分组。
 */
data class SimilarGroup(
    val id: Long = 0L,
    val title: String,
    val subtitle: String,
    val category: GroupCategory,
    val kind: MediaKind,
    val assets: List<MediaAsset>,
    val totalAssetCount: Int = assets.size,
    val totalSizeBytes: Long = assets.sumOf { it.size },
    val latestAssetTimeMillis: Long = assets.maxOfOrNull {
        maxOf(it.createdAt.time, it.dateAdded * 1000L)
    } ?: 0L
)
