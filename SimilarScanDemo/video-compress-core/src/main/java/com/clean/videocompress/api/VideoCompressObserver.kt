package com.clean.videocompress.api

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressResult

/**
 * 压缩回调。所有回调都会切回主线程，方便业务层直接更新 UI。
 */
interface VideoCompressObserver {
    fun onStart(asset: CompressVideoAsset) = Unit
    fun onProgress(progress: VideoCompressProgress) = Unit
    fun onSuccess(result: VideoCompressResult) = Unit
    fun onFailure(error: VideoCompressError) = Unit
    fun onCancelled(assetId: Long) = Unit
}
