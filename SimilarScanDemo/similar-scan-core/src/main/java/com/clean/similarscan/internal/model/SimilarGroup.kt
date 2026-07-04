package com.clean.similarscan.internal.model

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
    val assets: List<MediaAsset>,
    /**
     * 当前组的真实资源数量。
     */
    val totalAssetCount: Int = assets.size,
    /**
     * 当前组的真实资源总大小。默认使用已加载资源求和；
     * Other 分类会由 SQL 聚合填入真实值。
     */
    val totalSizeBytes: Long = assets.sumOf { it.size },
    /**
     * 当前组内最新资源的媒体时间，用于列表排序。
     *
     * 首页可能只加载少量预览资源，因此排序不能依赖 assets 中的样本时间；数据库查询会通过
     * MAX(created_at/date_added) 聚合出完整分组的最新时间。
     */
    val latestAssetTimeMillis: Long = assets.maxOfOrNull {
        maxOf(it.createdAt.time, it.dateAdded * 1000L)
    } ?: 0L
)
