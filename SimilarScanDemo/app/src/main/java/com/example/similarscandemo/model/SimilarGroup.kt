package com.example.similarscandemo.model

/**
 * 相似扫描最终展示的一组资源。
 */
data class SimilarGroup(
    /**
     * 数据库相似组 ID。普通分类由查询临时聚合，没有真实分组时使用 0。
     */
    val id: Long = 0L,
    val title: String,
    val subtitle: String,
    val category: GroupCategory,
    val kind: MediaKind,
    val assets: List<MediaAsset>
)
