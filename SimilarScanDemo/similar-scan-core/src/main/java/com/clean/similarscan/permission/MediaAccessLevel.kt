package com.clean.similarscan.permission

/**
 * 当前媒体库授权范围。
 *
 * Android 14+ 支持“选择照片和视频”的部分授权模式，这种情况下 MediaStore
 * 只能返回用户选中的资源，刚拍的新照片不会自动进入扫描范围。
 */
enum class MediaAccessLevel {
    NONE,
    PARTIAL_VISUAL,
    FULL_VISUAL,
    LEGACY_FULL
}
