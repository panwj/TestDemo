package com.example.similarscandemo.model

/**
 * 与竞品首页一致的媒体清理分类。
 */
enum class ProductCategoryType(val title: String, val grouped: Boolean) {
    SIMILAR("Similar", true),
    DUPLICATES("Duplicates", true),
    SIMILAR_SCREENSHOTS("Similar Screenshots", true),
    SIMILAR_VIDEOS("Similar Videos", true),
    OTHER_SCREENSHOTS("Other Screenshots", false),
    CHAT_PHOTOS("Chat Photos", false),
    SIMILAR_SCREEN_RECORDINGS("Similar Screen Rec", true),
    OTHER_SCREEN_RECORDINGS("Other Screen Rec", false),
    OTHER_VIDEOS("Other Videos", false),
    OTHER("Other", false)
}
