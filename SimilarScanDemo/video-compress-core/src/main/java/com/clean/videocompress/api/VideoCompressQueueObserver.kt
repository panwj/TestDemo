package com.clean.videocompress.api

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult

/**
 * 批量压缩队列回调。
 *
 * 队列是 SDK 层能力，Media3 与 Native 两种引擎共用。业务层可以用它驱动前台服务、
 * 通知、批量压缩页面或者失败重试逻辑。
 */
interface VideoCompressQueueObserver {
    fun onQueueStart(totalCount: Int) = Unit
    fun onItemStart(index: Int, totalCount: Int, asset: CompressVideoAsset) = Unit
    fun onItemProgress(index: Int, totalCount: Int, progress: VideoCompressProgress) = Unit
    fun onItemSuccess(index: Int, totalCount: Int, result: VideoCompressResult) = Unit
    fun onItemFailure(
        index: Int,
        totalCount: Int,
        request: VideoCompressRequest,
        error: VideoCompressError
    ) = Unit

    fun onQueueComplete(
        results: List<VideoCompressResult>,
        failures: List<VideoCompressQueueFailure>
    ) = Unit

    fun onQueueCancelled(
        results: List<VideoCompressResult>,
        failures: List<VideoCompressQueueFailure>
    ) = Unit
}

data class VideoCompressQueueFailure(
    val request: VideoCompressRequest,
    val error: VideoCompressError
)
