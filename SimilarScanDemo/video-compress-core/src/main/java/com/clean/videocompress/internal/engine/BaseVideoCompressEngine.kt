package com.clean.videocompress.internal.engine

import android.os.Handler
import android.os.Looper
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressResult

/**
 * 压缩引擎基类。
 *
 * 统一把回调切回主线程，业务层可以安全更新 UI。
 */
internal abstract class BaseVideoCompressEngine : VideoCompressEngine {
    protected val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 通知任务开始。
     */
    protected fun dispatchStart(observer: VideoCompressObserver, asset: CompressVideoAsset) {
        mainHandler.post { observer.onStart(asset) }
    }

    /**
     * 通知任务进度。
     */
    protected fun dispatchProgress(observer: VideoCompressObserver, progress: VideoCompressProgress) {
        mainHandler.post { observer.onProgress(progress) }
    }

    /**
     * 通知任务成功。
     */
    protected fun dispatchSuccess(observer: VideoCompressObserver, result: VideoCompressResult) {
        mainHandler.post { observer.onSuccess(result) }
    }

    /**
     * 通知任务失败。
     */
    protected fun dispatchFailure(observer: VideoCompressObserver, error: VideoCompressError) {
        mainHandler.post { observer.onFailure(error) }
    }

    /**
     * 通知任务被取消。
     */
    protected fun dispatchCancelled(observer: VideoCompressObserver, assetId: Long) {
        mainHandler.post { observer.onCancelled(assetId) }
    }
}
