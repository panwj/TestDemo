package com.clean.videocompress.internal.queue

import android.os.Handler
import android.os.Looper
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressQueueFailure
import com.clean.videocompress.api.VideoCompressQueueObserver
import com.clean.videocompress.api.VideoCompressQueueTask
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.clean.videocompress.internal.engine.VideoCompressEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单线程顺序压缩队列。
 *
 * Android 设备通常只有有限的硬件编码器资源，多个视频同时压缩很容易触发
 * 编码器初始化失败、内存抖动或系统杀进程。队列统一保证同一时间只有一个引擎任务运行。
 */
internal class SequentialVideoCompressQueue(
    private val engine: VideoCompressEngine
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 启动批量压缩队列。
     */
    fun start(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver
    ): VideoCompressQueueTask {
        val task = QueueTask()
        val executor = Executors.newSingleThreadExecutor()
        task.onCancel = {
            task.currentTask?.cancel()
            executor.shutdownNow()
        }
        executor.execute {
            runQueue(requests, observer, task)
            executor.shutdown()
        }
        return task
    }

    /**
     * 顺序执行队列中的每个请求。
     *
     * 每个任务通过 CountDownLatch 等待成功、失败或取消后，才会进入下一个任务。
     */
    private fun runQueue(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver,
        queueTask: QueueTask
    ) {
        val results = mutableListOf<VideoCompressResult>()
        val failures = mutableListOf<VideoCompressQueueFailure>()
        dispatch { observer.onQueueStart(requests.size) }
        for ((index, request) in requests.withIndex()) {
            if (queueTask.cancelled.get()) break
            val latch = CountDownLatch(1)
            var itemCompleted = false
            val itemIndex = index + 1
            val total = requests.size
            val itemTask = engine.compress(
                request,
                object : VideoCompressObserver {
                    override fun onStart(asset: CompressVideoAsset) {
                        dispatch { observer.onItemStart(itemIndex, total, asset) }
                    }

                    override fun onProgress(progress: VideoCompressProgress) {
                        dispatch { observer.onItemProgress(itemIndex, total, progress) }
                    }

                    override fun onSuccess(result: VideoCompressResult) {
                        itemCompleted = true
                        results += result
                        dispatch { observer.onItemSuccess(itemIndex, total, result) }
                        latch.countDown()
                    }

                    override fun onFailure(error: VideoCompressError) {
                        itemCompleted = true
                        failures += VideoCompressQueueFailure(request, error)
                        dispatch { observer.onItemFailure(itemIndex, total, request, error) }
                        latch.countDown()
                    }

                    override fun onCancelled(assetId: Long) {
                        itemCompleted = true
                        failures += VideoCompressQueueFailure(request, VideoCompressError.Cancelled)
                        latch.countDown()
                    }
                }
            )
            queueTask.currentTask = itemTask
            latch.await()
            queueTask.currentTask = null
            if (!itemCompleted || queueTask.cancelled.get()) break
        }
        if (queueTask.cancelled.get()) {
            dispatch { observer.onQueueCancelled(results, failures) }
        } else {
            dispatch { observer.onQueueComplete(results, failures) }
        }
    }

    /**
     * 队列回调统一切到主线程。
     */
    private fun dispatch(block: () -> Unit) {
        mainHandler.post(block)
    }

    private class QueueTask : VideoCompressQueueTask {
        val cancelled = AtomicBoolean(false)
        @Volatile
        var currentTask: VideoCompressTask? = null
        @Volatile
        var onCancel: (() -> Unit)? = null

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                onCancel?.invoke()
                currentTask?.cancel()
            }
        }
    }
}
