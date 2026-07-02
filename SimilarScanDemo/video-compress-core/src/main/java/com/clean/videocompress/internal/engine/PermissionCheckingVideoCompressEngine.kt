package com.clean.videocompress.internal.engine

import android.content.Context
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressPermissionOperation
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.permission.VideoCompressPermissionChecker

/**
 * 权限保护层。
 *
 * 单任务和队列任务都会经过该引擎，确保没有视频读取权限或老系统没有写入权限时，
 * 压缩任务不会进入真正的编码流程。
 */
internal class PermissionCheckingVideoCompressEngine(
    private val context: Context,
    private val delegate: VideoCompressEngine
) : BaseVideoCompressEngine() {
    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        val error = permissionError()
        if (error != null) {
            dispatchFailure(observer, error)
            return CompletedVideoCompressTask
        }
        return delegate.compress(request, observer)
    }

    override fun close() {
        delegate.close()
    }

    private fun permissionError(): VideoCompressError? {
        return when {
            !VideoCompressPermissionChecker.hasVideoAccess(context) ->
                VideoCompressError.PermissionDenied(VideoCompressPermissionOperation.READ_VIDEO)

            !VideoCompressPermissionChecker.hasSaveAccess(context) ->
                VideoCompressError.PermissionDenied(VideoCompressPermissionOperation.SAVE_VIDEO)

            else -> null
        }
    }
}
