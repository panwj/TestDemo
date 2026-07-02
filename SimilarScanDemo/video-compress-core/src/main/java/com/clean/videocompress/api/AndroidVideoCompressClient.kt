package com.clean.videocompress.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoBucket
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.internal.engine.CompletedVideoCompressTask
import com.clean.videocompress.internal.engine.VideoCompressEngine
import com.clean.videocompress.internal.engine.PermissionCheckingVideoCompressEngine
import com.clean.videocompress.internal.engine.media3.Media3VideoCompressEngine
import com.clean.videocompress.internal.engine.nativecodec.NativeCodecVideoCompressEngine
import com.clean.videocompress.internal.media.VideoBucketBuilder
import com.clean.videocompress.internal.media.VideoMediaRepository
import com.clean.videocompress.internal.media.VideoStoreWriter
import com.clean.videocompress.internal.queue.SequentialVideoCompressQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 平台默认实现。
 *
 * 这里负责串起 MediaStore 读取、分桶、压缩引擎选择、权限保护层和顺序队列。
 * 对外暴露的是 VideoCompressClient，业务层无需感知这些内部对象。
 */
internal class AndroidVideoCompressClient(
    private val context: Context,
    private val config: VideoCompressConfig
) : VideoCompressClient {
    private val repository = VideoMediaRepository(context)
    private val bucketBuilder = VideoBucketBuilder(config)
    private val writer = VideoStoreWriter(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var engine: VideoCompressEngine? = null

    /**
     * 读取系统媒体库中的视频资源。
     */
    override fun loadVideos(): List<CompressVideoAsset> {
        return repository.loadVideos()
    }

    /**
     * 根据配置把视频划分为压缩首页展示的分桶。
     */
    override fun buildBuckets(videos: List<CompressVideoAsset>): List<VideoBucket> {
        return bucketBuilder.build(videos)
    }

    /**
     * 执行单个视频压缩。
     *
     * 如果 client 已释放，立即回调 SDK_CLOSED，避免业务层误用后崩溃。
     */
    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        if (closed.get()) {
            mainHandler.post { observer.onFailure(VideoCompressError.SdkClosed) }
            return CompletedVideoCompressTask
        }
        return engine().compress(request, observer)
    }

    /**
     * 执行批量压缩。
     *
     * 队列始终是单任务顺序执行，不做并发压缩，避免多个任务抢占硬件编码器。
     */
    override fun compressQueue(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver
    ): VideoCompressQueueTask {
        if (closed.get()) {
            return closedQueueTask(requests, observer)
        }
        return SequentialVideoCompressQueue(engine()).start(requests, observer)
    }

    /**
     * 释放内部引擎和线程。
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine?.close()
            engine = null
        }
    }

    /**
     * 懒加载压缩引擎。
     *
     * 引擎外面统一包一层权限检查，确保单任务和队列任务都执行同样的权限前置校验。
     */
    private fun engine(): VideoCompressEngine {
        val current = engine
        if (current != null) return current
        val rawEngine = when (config.engineType) {
            VideoCompressEngineType.MEDIA3_TRANSFORMER -> Media3VideoCompressEngine(context, writer)
            VideoCompressEngineType.NATIVE_CODEC -> NativeCodecVideoCompressEngine(context, writer)
        }
        return PermissionCheckingVideoCompressEngine(context, rawEngine).also {
            engine = it
        }
    }

    /**
     * client 关闭后返回的队列占位任务。
     */
    private fun closedQueueTask(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver
    ): VideoCompressQueueTask {
        val task = object : VideoCompressQueueTask {
            override fun cancel() = Unit
        }
        mainHandler.post {
            observer.onQueueStart(requests.size)
            val failures = requests.map { VideoCompressQueueFailure(it, VideoCompressError.SdkClosed) }
            requests.forEachIndexed { index, request ->
                observer.onItemFailure(index + 1, requests.size, request, VideoCompressError.SdkClosed)
            }
            observer.onQueueComplete(emptyList(), failures)
        }
        return task
    }
}
