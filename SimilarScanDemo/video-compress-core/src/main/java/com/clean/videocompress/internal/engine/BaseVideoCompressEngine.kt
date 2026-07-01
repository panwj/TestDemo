package com.clean.videocompress.internal.engine

import android.os.Handler
import android.os.Looper
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressResult

internal abstract class BaseVideoCompressEngine : VideoCompressEngine {
    protected val mainHandler = Handler(Looper.getMainLooper())

    protected fun dispatchStart(observer: VideoCompressObserver, asset: CompressVideoAsset) {
        mainHandler.post { observer.onStart(asset) }
    }

    protected fun dispatchProgress(observer: VideoCompressObserver, progress: VideoCompressProgress) {
        mainHandler.post { observer.onProgress(progress) }
    }

    protected fun dispatchSuccess(observer: VideoCompressObserver, result: VideoCompressResult) {
        mainHandler.post { observer.onSuccess(result) }
    }

    protected fun dispatchFailure(observer: VideoCompressObserver, error: VideoCompressError) {
        mainHandler.post { observer.onFailure(error) }
    }

    protected fun dispatchCancelled(observer: VideoCompressObserver, assetId: Long) {
        mainHandler.post { observer.onCancelled(assetId) }
    }
}
