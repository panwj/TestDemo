package com.clean.similarscan.internal.model

/**
 * Demo 支持的媒体分类。
 *
 * 竞品会把普通照片、截图、视频、录屏分到不同文件夹中；这里保留同样的分类边界，
 * 这样每类资源可以使用自己的相似阈值。
 */
enum class MediaKind {
    PHOTO,
    SCREENSHOT,
    VIDEO,
    SCREEN_RECORDING
}
