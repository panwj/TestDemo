package com.clean.videocompress.internal.engine

import com.clean.videocompress.api.VideoCompressTask

/**
 * 已结束任务占位对象。
 *
 * 当前置权限、存储空间或 SDK 状态检查失败时，SDK 会立即回调失败，同时仍返回
 * 一个可安全 cancel 的任务对象，避免业务层额外处理 null。
 */
internal object CompletedVideoCompressTask : VideoCompressTask {
    override fun cancel() = Unit
}
