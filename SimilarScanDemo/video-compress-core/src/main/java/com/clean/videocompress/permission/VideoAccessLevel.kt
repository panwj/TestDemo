package com.clean.videocompress.permission

/**
 * 视频媒体权限状态。
 */
enum class VideoAccessLevel {
    /**
     * 没有任何视频读取权限。
     */
    NONE,

    /**
     * Android 14+ 用户只授权了部分视频资源。
     */
    PARTIAL_VIDEO,

    /**
     * Android 13+ 完整视频读取权限。
     */
    FULL_VIDEO,

    /**
     * Android 12 及以下通过 READ_EXTERNAL_STORAGE 获得媒体读取权限。
     */
    LEGACY_FULL
}
