package com.clean.videocompress.internal.engine.nativecodec

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.clean.videocompress.api.model.VideoCompressStage
import com.clean.videocompress.internal.engine.BaseVideoCompressEngine
import com.clean.videocompress.internal.media.VideoStoreWriter
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 原生备用引擎。
 *
 * 当前实现保持代码路径独立，使用 MediaExtractor/MediaMuxer 完成安全转封装和保存；
 * 后续如果需要完整原生转码，可在该包内补充 MediaCodec 解码/编码流程，不影响 Media3 引擎。
 */
internal class NativeCodecVideoCompressEngine(
    private val context: Context,
    private val writer: VideoStoreWriter
) : BaseVideoCompressEngine() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        val task = NativeCodecVideoCompressTask()
        val startTime = System.currentTimeMillis()
        dispatchStart(observer, request.asset)
        executor.execute {
            val outputFile = File(
                context.cacheDir,
                "video_compress/native_${request.asset.id}_${System.currentTimeMillis()}.mp4"
            )
            outputFile.parentFile?.mkdirs()
            try {
                dispatchProgress(
                    observer,
                    VideoCompressProgress(request.asset.id, VideoCompressStage.PREPARING, 0, 0L)
                )
                remux(request, outputFile, observer, task, startTime)
                if (task.cancelled) {
                    outputFile.delete()
                    dispatchCancelled(observer, request.asset.id)
                    return@execute
                }
                dispatchProgress(
                    observer,
                    VideoCompressProgress(
                        request.asset.id,
                        VideoCompressStage.SAVING_TO_MEDIASTORE,
                        99,
                        System.currentTimeMillis() - startTime
                    )
                )
                val saved = writer.saveToMediaStore(
                    outputFile,
                    "compressed_${request.asset.displayName.substringBeforeLast('.')}.mp4"
                )
                val elapsed = System.currentTimeMillis() - startTime
                dispatchProgress(
                    observer,
                    VideoCompressProgress(request.asset.id, VideoCompressStage.COMPLETED, 100, elapsed)
                )
                dispatchSuccess(
                    observer,
                    VideoCompressResult(
                        sourceAsset = request.asset,
                        outputUri = saved.uri,
                        outputSizeBytes = saved.sizeBytes,
                        savedBytes = (request.asset.sizeBytes - saved.sizeBytes).coerceAtLeast(0L),
                        elapsedMs = elapsed
                    )
                )
            } catch (error: Throwable) {
                outputFile.delete()
                if (task.cancelled) {
                    dispatchCancelled(observer, request.asset.id)
                } else {
                    dispatchFailure(observer, VideoCompressError.EngineFailed(error.message, error))
                }
            } finally {
                outputFile.delete()
            }
        }
        return task
    }

    private fun remux(
        request: VideoCompressRequest,
        outputFile: File,
        observer: VideoCompressObserver,
        task: NativeCodecVideoCompressTask,
        startTime: Long
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            context.contentResolver.openFileDescriptor(request.asset.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("Cannot open source video")
            val trackMap = mutableMapOf<Int, Int>()
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(track)
                    if (muxer == null) {
                        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    }
                    trackMap[track] = muxer!!.addTrack(format)
                }
            }
            val activeMuxer = muxer ?: throw IllegalStateException("No supported track")
            activeMuxer.start()
            val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
            val info = android.media.MediaCodec.BufferInfo()
            var lastProgress = 0
            while (!task.cancelled) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val outputTrack = trackMap[trackIndex]
                if (outputTrack != null) {
                    info.offset = 0
                    info.size = extractor.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    activeMuxer.writeSampleData(outputTrack, buffer, info)
                    val progress = if (request.asset.durationMs > 0) {
                        ((info.presentationTimeUs / 1000L) * 95L / request.asset.durationMs)
                            .toInt()
                            .coerceIn(1, 95)
                    } else {
                        50
                    }
                    if (progress - lastProgress >= 3) {
                        lastProgress = progress
                        dispatchProgress(
                            observer,
                            VideoCompressProgress(
                                request.asset.id,
                                VideoCompressStage.TRANSCODING,
                                progress,
                                System.currentTimeMillis() - startTime
                            )
                        )
                    }
                }
                extractor.advance()
            }
        } finally {
            try {
                muxer?.stop()
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1024 * 1024
    }
}
