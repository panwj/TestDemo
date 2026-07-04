package com.clean.similarscan.api.model

/**
 * SDK 对外首页分类。
 *
 * title/grouped 保留给接入方快速构建默认 UI；正式产品仍可自行映射本地化文案。
 */
enum class ProductCategoryType(val title: String, val grouped: Boolean) {
    SIMILAR("Similar", true),
    DUPLICATES("Duplicates", true),
    SIMILAR_SCREENSHOTS("Similar Screenshots", true),
    SIMILAR_VIDEOS("Similar Videos", true),
    OTHER_SCREENSHOTS("Other Screenshots", false),
    OTHER_VIDEOS("Other Videos", false),
    OTHER("Other", false)
}
