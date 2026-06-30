package com.clean.similarscan.permission

/**
 * 当前媒体库授权范围。
 *
 * Android 14+ 支持“选择照片和视频”的部分授权模式，这种情况下 MediaStore
 * 只能返回用户选中的资源，刚拍的新照片不会自动进入扫描范围。
 */
enum class MediaAccessLevel {
    /**
     * 没有任何可用媒体读取权限。
     *
     * SDK 不应启动扫描；宿主应用应引导用户授予图片或视频读取权限。
     */
    NONE,

    /**
     * 仅具备图片读取能力。
     *
     * 常见于 Android 13+ 用户只授予 READ_MEDIA_IMAGES。SDK 只扫描图片和截图，
     * 不能把历史视频结果视为已删除。
     */
    IMAGES_ONLY,

    /**
     * 仅具备视频读取能力。
     *
     * 常见于 Android 13+ 用户只授予 READ_MEDIA_VIDEO。SDK 只扫描视频和录屏，
     * 不能把历史图片结果视为已删除。
     */
    VIDEOS_ONLY,

    /**
     * Android 14+ 的用户选择媒体授权。
     *
     * MediaStore 只会返回用户选择的图片/视频子集。该模式可以扫描当前可见资源，
     * 但不具备完整图库视图，因此不能执行“未返回资源即删除”的清理逻辑。
     */
    PARTIAL_VISUAL,

    /**
     * Android 13+ 的完整图片和视频授权。
     *
     * 同时具备 READ_MEDIA_IMAGES 和 READ_MEDIA_VIDEO。SDK 可以扫描完整图片/视频库，
     * 全量扫描完成后允许清理 MediaStore 未返回的旧记录。
     */
    FULL_VISUAL,

    /**
     * Android 12 及以下的传统完整媒体授权。
     *
     * 基于 READ_EXTERNAL_STORAGE，图片和视频共用同一个读取权限。语义上等价于完整图库访问。
     */
    LEGACY_FULL
}

/**
 * SDK 对外暴露的媒体访问状态快照。
 *
 * accessLevel 用于 UI 文案和业务分支；canReadImages/canReadVideos 用于决定实际扫描范围；
 * hasFullVisualAccess 用于判断是否允许把 MediaStore 未返回的旧资源视为已删除。
 */
data class MediaAccessState(
    /**
     * 面向业务判断和 UI 文案的粗粒度授权等级。
     */
    val level: MediaAccessLevel,

    /**
     * 当前是否可以从 MediaStore.Images 读取图片资源。
     */
    val canReadImages: Boolean,

    /**
     * 当前是否可以从 MediaStore.Video 读取视频资源。
     */
    val canReadVideos: Boolean,

    /**
     * 是否拥有完整图片 + 视频图库访问能力。
     *
     * 只有该字段为 true 时，SDK 才能在完整全量扫描完成后把 MediaStore 未返回的旧资源
     * 判定为已删除并清理本地缓存。
     */
    val hasFullVisualAccess: Boolean,

    /**
     * 是否处于 Android 14+ 用户选择媒体的部分授权状态。
     *
     * 部分授权下即使可以读取图片或视频，也只能看到用户选择的资源子集。
     */
    val hasPartialVisualAccess: Boolean
)
