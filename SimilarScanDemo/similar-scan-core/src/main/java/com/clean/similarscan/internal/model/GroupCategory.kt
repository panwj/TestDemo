package com.clean.similarscan.internal.model

/**
 * 扫描结果的产品分类。
 *
 * DUPLICATE 用于完全相同或高度确定的重复资源；
 * SIMILAR 用于视觉上相似、需要用户人工确认是否清理的资源。
 */
enum class GroupCategory {
    DUPLICATE,
    SIMILAR,
    OTHER
}
