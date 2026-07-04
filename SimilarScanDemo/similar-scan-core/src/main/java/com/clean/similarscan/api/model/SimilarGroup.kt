package com.clean.similarscan.api.model

/**
 * SDK 对外相似/相同/其他分组。
 *
 * Similar/Duplicate 分类下，一个 SimilarGroup 表示一组相似或相同资源；
 * Other 类平铺分类下，SDK 会生成合成分组承载该分类的资源列表。
 */
data class SimilarGroup(
    /** 数据库分组 id。合成 Other 分组可能没有真实 groupId，默认值为 0。 */
    val id: Long = 0L,
    /** 分组标题，供默认 UI 直接展示；正式产品可自行映射文案。 */
    val title: String,
    /** 分组副标题，通常展示数量和大小等摘要信息。 */
    val subtitle: String,
    /** 分组语义：相同、相似或其他。 */
    val category: GroupCategory,
    /** 分组内资源的媒体类型。产品展示层可能会把多个底层类型归并到同一分类。 */
    val kind: MediaKind,
    /** 本次接口返回的资源列表；首页预览场景中可能只是完整分组的一部分。 */
    val assets: List<MediaAsset>,
    /** 完整分组内的真实资源数量，不受本次 assets 预览数量影响。 */
    val totalAssetCount: Int = assets.size,
    /** 完整分组内的真实文件总大小，不受本次 assets 预览数量影响。 */
    val totalSizeBytes: Long = assets.sumOf { it.size },
    /**
     * 完整分组中最新媒体资源的时间戳。
     *
     * 数据库读取时会对完整组做聚合，因此首页即使只加载少量预览图，排序也仍然稳定。
     */
    val latestAssetTimeMillis: Long = assets.maxOfOrNull {
        maxOf(it.createdAt.time, it.dateAdded * 1000L)
    } ?: 0L
)
