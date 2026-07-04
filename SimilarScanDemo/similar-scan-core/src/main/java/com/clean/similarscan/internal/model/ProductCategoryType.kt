package com.clean.similarscan.internal.model

/**
 * 与产品首页一致的媒体清理分类。
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
