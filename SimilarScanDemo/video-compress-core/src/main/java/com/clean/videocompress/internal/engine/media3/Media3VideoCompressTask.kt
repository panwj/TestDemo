package com.clean.videocompress.internal.engine.media3

import com.clean.videocompress.api.VideoCompressTask

internal class Media3VideoCompressTask(
    private val onCancel: () -> Unit
) : VideoCompressTask {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var finished: Boolean = false
        private set

    override fun cancel() {
        cancelled = true
        onCancel()
    }

    fun markFinished() {
        finished = true
    }
}
