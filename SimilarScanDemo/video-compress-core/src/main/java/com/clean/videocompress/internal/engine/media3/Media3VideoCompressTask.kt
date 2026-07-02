package com.clean.videocompress.internal.engine.media3

import com.clean.videocompress.api.VideoCompressTask

/**
 * Media3 单次压缩任务句柄。
 */
internal class Media3VideoCompressTask(
    private val onCancel: () -> Unit
) : VideoCompressTask {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var finished: Boolean = false
        private set

    /**
     * 取消 Media3 Transformer，并清理临时文件。
     */
    override fun cancel() {
        cancelled = true
        onCancel()
    }

    /**
     * 标记 Media3 转码流程已经结束，避免继续轮询进度。
     */
    fun markFinished() {
        finished = true
    }
}
