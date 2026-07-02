package com.clean.videocompress.api

import android.content.Context

/**
 * 视频压缩 SDK 创建入口。
 *
 * SDK 只提供视频读取、压缩执行、结果保存和权限状态检查能力；免费次数、PRO、
 * 弹窗和删除原视频等产品业务由接入方自行处理。
 */
object VideoCompressSdk {
    /**
     * 创建 SDK client。
     *
     * context 会转成 applicationContext 保存，避免持有 Activity。
     */
    fun create(context: Context, config: VideoCompressConfig = VideoCompressConfig()): VideoCompressClient {
        return AndroidVideoCompressClient(context.applicationContext, config)
    }
}
