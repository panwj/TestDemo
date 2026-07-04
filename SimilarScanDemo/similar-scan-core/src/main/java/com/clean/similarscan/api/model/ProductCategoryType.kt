package com.clean.similarscan.api.model

/**
 * SDK 对外首页分类。
 *
 * title/grouped 保留给接入方快速构建默认 UI；正式产品仍可自行映射本地化文案。
 */
enum class ProductCategoryType(val title: String, val grouped: Boolean) {
    /** 普通照片相似分组，进入详情后通常先展示分组，再进入单组资源列表。 */
    SIMILAR("Similar", true),

    /** 相同图片/截图分组，按字节或强视觉一致性归类，不再重复计入 Similar。 */
    DUPLICATES("Duplicates", true),

    /** 截图相似分组，使用更严格阈值降低 UI 截图之间的误合并。 */
    SIMILAR_SCREENSHOTS("Similar Screenshots", true),

    /** 视频相似分组；产品展示层会把普通视频和录屏统一归入该分类。 */
    SIMILAR_VIDEOS("Similar Videos", true),

    /** 未进入相似/相同结果的截图资源，适合平铺分页展示。 */
    OTHER_SCREENSHOTS("Other Screenshots", false),

    /** 未进入相似结果的普通视频和录屏资源，适合平铺分页展示。 */
    OTHER_VIDEOS("Other Videos", false),

    /** 未进入其他清理分类的普通照片资源，适合平铺分页展示。 */
    OTHER("Other", false)
}
