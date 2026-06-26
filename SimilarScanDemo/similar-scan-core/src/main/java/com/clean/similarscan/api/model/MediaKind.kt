package com.clean.similarscan.api.model

/**
 * SDK 对外媒体类型。
 *
 * 仅描述产品分类边界，不暴露内部数据库字段或算法实现细节。
 */
enum class MediaKind {
    PHOTO,
    SCREENSHOT,
    VIDEO,
    SCREEN_RECORDING
}
