package com.clean.similarscan.api.model

/**
 * SDK 对外产品分类结果。
 */
data class ProductCategory(
    val type: ProductCategoryType,
    val groups: List<SimilarGroup>
) {
    val itemCount: Int
        get() = groups.sumOf { it.totalAssetCount }

    val assets: List<MediaAsset>
        get() = groups.flatMap { it.assets }.distinctBy { it.kind to it.id }

    val totalSize: Long
        get() = groups.sumOf { it.totalSizeBytes }
}
