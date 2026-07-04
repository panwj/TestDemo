package com.clean.similarscan.api.model

/**
 * SDK 对外产品分类结果。
 *
 * 一个 ProductCategory 对应首页上的一个业务分类，例如 Similar、Duplicates、
 * Similar Videos 或 Other。分类内部仍以 SimilarGroup 为单位组织数据：
 * - grouped=true 的分类表示多个相似/相同分组；
 * - grouped=false 的分类通常只有一个合成分组，用于承载平铺资源列表。
 */
data class ProductCategory(
    /** 产品分类类型，决定首页展示文案、是否按分组进入详情，以及业务层入口。 */
    val type: ProductCategoryType,
    /** 当前分类下的分组集合。首页预览场景中，每个分组的 assets 可能只包含少量预览资源。 */
    val groups: List<SimilarGroup>
) {
    /**
     * 当前分类下的真实资源总数。
     *
     * 该值来自 SimilarGroup.totalAssetCount 聚合，不受 previewAssetLimit 影响。
     */
    val itemCount: Int
        get() = groups.sumOf { it.totalAssetCount }

    /**
     * 当前接口调用实际返回的资源样本。
     *
     * 首页传入 previewAssetLimit 时，这里只包含每个分组的预览资源，不代表分类完整资源。
     * 分组详情或平铺详情需要通过分页接口继续加载。
     */
    val assets: List<MediaAsset>
        get() = groups.flatMap { it.assets }.distinctBy { it.kind to it.id }

    /**
     * 当前分类下的真实文件总大小。
     *
     * 该值来自 SimilarGroup.totalSizeBytes 聚合，不受预览资源数量影响。
     */
    val totalSize: Long
        get() = groups.sumOf { it.totalSizeBytes }
}
