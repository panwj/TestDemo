package com.clean.similarscan.api.model

/**
 * SDK 对外媒体类型。
 *
 * 仅描述产品分类边界，不暴露内部数据库字段或算法实现细节。
 */
enum class MediaKind {
    /** 普通照片。 */
    PHOTO,

    /** 截图，通常根据 DISPLAY_NAME/pathHint 中的 screenshot/screen_shot 等规则识别。 */
    SCREENSHOT,

    /** 普通视频。 */
    VIDEO,

    /** 录屏，通常根据 DISPLAY_NAME/pathHint 中的 screen_recording/recording 等规则识别。 */
    SCREEN_RECORDING
}
