package com.clean.videocompress.api

import android.content.Context
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoBucket
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.internal.engine.VideoCompressEngine
import com.clean.videocompress.internal.engine.media3.Media3VideoCompressEngine
import com.clean.videocompress.internal.engine.nativecodec.NativeCodecVideoCompressEngine
import com.clean.videocompress.internal.media.VideoBucketBuilder
import com.clean.videocompress.internal.media.VideoMediaRepository
import com.clean.videocompress.internal.media.VideoStoreWriter
import com.clean.videocompress.internal.queue.SequentialVideoCompressQueue

internal class AndroidVideoCompressClient(
    private val context: Context,
    private val config: VideoCompressConfig
) : VideoCompressClient {
    private val repository = VideoMediaRepository(context)
    private val bucketBuilder = VideoBucketBuilder(config)
    private val writer = VideoStoreWriter(context)
    private val engine: VideoCompressEngine = when (config.engineType) {
        VideoCompressEngineType.MEDIA3_TRANSFORMER -> Media3VideoCompressEngine(context, writer)
        VideoCompressEngineType.NATIVE_CODEC -> NativeCodecVideoCompressEngine(context, writer)
    }

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
        return engine.compress(request, observer)
    }

    override fun compressQueue(
        requests: List<VideoCompressRequest>,
        observer: VideoCompressQueueObserver
    ): VideoCompressQueueTask {
        return SequentialVideoCompressQueue(engine).start(requests, observer)
    }
}
