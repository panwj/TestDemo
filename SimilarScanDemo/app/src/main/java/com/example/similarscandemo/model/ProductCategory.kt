package com.example.similarscandemo.model

/**
 * 首页展示的产品分类。
 *
 * grouped=true 的分类保留算法生成的多个相似组；其他分类使用单个资源集合。
 */
data class ProductCategory(
    val type: ProductCategoryType,
    val groups: List<SimilarGroup>
) {
    val assets: List<MediaAsset>
        get() = groups.flatMap { it.assets }.distinctBy { it.kind to it.id }

    val totalSize: Long
        get() = assets.sumOf { it.size }
}
