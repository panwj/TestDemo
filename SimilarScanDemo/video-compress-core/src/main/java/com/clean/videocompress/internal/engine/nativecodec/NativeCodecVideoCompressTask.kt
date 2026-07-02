package com.clean.videocompress.internal.engine.nativecodec

import com.clean.videocompress.api.VideoCompressTask

/**
 * Native 备用引擎任务句柄。
 */
internal class NativeCodecVideoCompressTask : VideoCompressTask {
    @Volatile
    var cancelled: Boolean = false
        private set

    /**
     * 标记取消，编码循环会在下一次检查时退出。
     */
    override fun cancel() {
        cancelled = true
    }
}
