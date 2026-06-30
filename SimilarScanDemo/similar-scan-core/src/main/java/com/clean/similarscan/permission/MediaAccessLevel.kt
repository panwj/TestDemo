package com.clean.similarscan.permission

/**
 * 当前媒体库授权范围。
 *
 * Android 14+ 支持“选择照片和视频”的部分授权模式，这种情况下 MediaStore
 * 只能返回用户选中的资源，刚拍的新照片不会自动进入扫描范围。
 */
enum class MediaAccessLevel {
    NONE,
    IMAGES_ONLY,
    VIDEOS_ONLY,
    PARTIAL_VISUAL,
    FULL_VISUAL,
    LEGACY_FULL
}

/**
 * SDK 对外暴露的媒体访问状态快照。
 *
 * accessLevel 用于 UI 文案和业务分支；canReadImages/canReadVideos 用于决定实际扫描范围；
 * hasFullVisualAccess 用于判断是否允许把 MediaStore 未返回的旧资源视为已删除。
 */
data class MediaAccessState(
    val level: MediaAccessLevel,
    val canReadImages: Boolean,
    val canReadVideos: Boolean,
    val hasFullVisualAccess: Boolean,
    val hasPartialVisualAccess: Boolean
)
