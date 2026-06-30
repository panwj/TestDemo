package com.clean.similarscan.internal.model

/**
 * SDK 支持的媒体分类。
 *
 * 普通照片、截图、视频、录屏保留独立分类边界，这样每类资源可以使用自己的相似阈值。
 */
enum class MediaKind {
    PHOTO,
    SCREENSHOT,
    VIDEO,
    SCREEN_RECORDING
}
