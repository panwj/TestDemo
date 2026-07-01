package com.clean.videocompress.internal.engine.nativecodec

import com.clean.videocompress.api.VideoCompressTask

internal class NativeCodecVideoCompressTask : VideoCompressTask {
    @Volatile
    var cancelled: Boolean = false
        private set

    override fun cancel() {
        cancelled = true
    }
}
