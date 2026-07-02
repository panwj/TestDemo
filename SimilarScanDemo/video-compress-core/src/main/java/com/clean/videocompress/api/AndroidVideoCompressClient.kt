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

    override fun loadVideos(): List<CompressVideoAsset> {
        return repository.loadVideos()
    }

    override fun buildBuckets(videos: List<CompressVideoAsset>): List<VideoBucket> {
        return bucketBuilder.build(videos)
    }

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

    override fun compressQueue(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver
    ): VideoCompressQueueTask {
        if (closed.get()) {
            return closedQueueTask(requests, observer)
        }
        return SequentialVideoCompressQueue(engine()).start(requests, observer)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine?.close()
            engine = null
        }
    }

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
