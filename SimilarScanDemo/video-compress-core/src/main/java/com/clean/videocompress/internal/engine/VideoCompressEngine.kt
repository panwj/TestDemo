package com.clean.videocompress.internal.engine

import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.VideoCompressRequest

internal interface VideoCompressEngine {
    fun compress(request: VideoCompressRequest, observer: VideoCompressObserver): VideoCompressTask
}
