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
     * 当前组的真实资源数量。首页 Other 分类只加载少量预览资源，
     * 因此不能用 assets.size 作为统计口径。
     */
    val totalAssetCount: Int = assets.size,
    /**
     * 当前组的真实资源总大小。默认使用已加载资源求和；
     * Other 分类会由 SQL 聚合填入真实值。
     */
    val totalSizeBytes: Long = assets.sumOf { it.size }
)
