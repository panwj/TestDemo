package com.clean.videocompress.api

/**
 * 批量压缩队列任务句柄。
 */
interface VideoCompressQueueTask {
    fun cancel()
}
