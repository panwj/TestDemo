package com.clean.similarscan.api.model

/**
 * SDK 对外分组类型。
 */
enum class GroupCategory {
    /** 相同资源分组，优先级高于 Similar，进入该类后不再重复计入相似分类。 */
    DUPLICATE,

    /** 相似资源分组，表示视觉接近但不一定字节完全相同。 */
    SIMILAR,

    /** 未进入相同/相似分组的资源集合，用于平铺类清理入口。 */
    OTHER
}
