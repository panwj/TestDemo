package com.clean.videocompress.internal.engine.nativecodec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.clean.videocompress.api.model.VideoCompressStage
import com.clean.videocompress.internal.engine.BaseVideoCompressEngine
import com.clean.videocompress.internal.media.VideoStoreWriter
import com.clean.videocompress.internal.policy.VideoFormatInspector
import com.clean.videocompress.internal.util.BitrateCalculator
import com.clean.videocompress.internal.util.StorageSpaceChecker
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生备用引擎。
 *
 * 该实现保持与 Media3 引擎独立：使用 MediaExtractor 读取视频，MediaCodec 将视频
 * 重新编码为 H.264，并用 MediaMuxer 写出 MP4；音频轨在格式支持时透传。
 * 三档压缩通过 BitrateCalculator 映射为目标码率，保证 Native 引擎也能真正减小体积。
 */
internal class NativeCodecVideoCompressEngine(
    private val context: Context,
    private val writer: VideoStoreWriter
) : BaseVideoCompressEngine() {
    private val executor = Executors.newSingleThreadExecutor()
    private val formatInspector = VideoFormatInspector(context)
    private val closed = AtomicBoolean(false)
    private val activeTasks = Collections.synchronizedSet(mutableSetOf<NativeCodecVideoCompressTask>())

    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        val task = NativeCodecVideoCompressTask()
        if (closed.get()) {
            dispatchFailure(observer, VideoCompressError.SdkClosed)
            return task
        }
        val profile = formatInspector.inspect(request.asset)
        if (profile.isHdr) {
            dispatchFailure(
                observer,
                VideoCompressError.UnsupportedFormat(
                    "HDR input is not supported by Native Codec fallback. Use Media3 Transformer."
                )
            )
            return task
        }
        if (profile.isHevc) {
            dispatchFailure(
                observer,
                VideoCompressError.UnsupportedFormat(
                    "HEVC input is only supported by the Media3 Transformer path."
                )
            )
            return task
        }
        val targetBitrate = BitrateCalculator.targetBitrate(request.asset, request.option)
        StorageSpaceChecker.checkBeforeCompress(
            context = context,
            estimatedOutputBytes = StorageSpaceChecker.estimateOutputBytes(request.asset, targetBitrate),
            sourceSizeBytes = request.asset.sizeBytes
        )?.let { error ->
            dispatchFailure(observer, error)
            return task
        }
        activeTasks += task
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
                transcode(request, outputFile, observer, task, startTime)
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
                activeTasks.remove(task)
            }
        }
        return task
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeTasks.toList().forEach { it.cancel() }
            activeTasks.clear()
            executor.shutdownNow()
        }
    }

    private fun transcode(
        request: VideoCompressRequest,
        outputFile: File,
        observer: VideoCompressObserver,
        task: NativeCodecVideoCompressTask,
        startTime: Long
    ) {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            context.contentResolver.openFileDescriptor(request.asset.uri, "r")?.use { pfd ->
                videoExtractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("Cannot open source video")

            val videoTrack = selectTrack(videoExtractor, "video/")
            if (videoTrack < 0) throw IllegalStateException("No video track")
            videoExtractor.selectTrack(videoTrack)
            val inputFormat = videoExtractor.getTrackFormat(videoTrack)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Missing video mime")
            val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH).makeEven()
            val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT).makeEven()
            val frameRate = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
            } else {
                DEFAULT_FRAME_RATE
            }
            val outputFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BitrateCalculator.targetBitrate(request.asset, request.option))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }

            encoder = MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, encoderInputSurface, null, 0)
            decoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (inputFormat.containsKey("rotation-degrees")) {
                muxer.setOrientationHint(inputFormat.getInteger("rotation-degrees"))
            }

            audioExtractor = createAudioExtractor(request)
            val audioTrack = audioExtractor?.let { selectTrack(it, "audio/") } ?: -1
            val audioFormat = if (audioTrack >= 0) {
                audioExtractor?.selectTrack(audioTrack)
                audioExtractor?.getTrackFormat(audioTrack)
            } else {
                null
            }

            encodeVideo(
                request = request,
                videoExtractor = videoExtractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                audioFormat = audioFormat,
                observer = observer,
                task = task,
                startTime = startTime
            )?.let { audioOutputTrack ->
                val audio = audioExtractor
                if (!task.cancelled && audio != null && audioOutputTrack >= 0) {
                    copyAudioSamples(audio, muxer, audioOutputTrack, task)
                }
            }
        } finally {
            try {
                decoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                decoder?.release()
            } catch (_: Throwable) {
            }
            try {
                encoder?.stop()
            } catch (_: Throwable) {
            }
            try {
                encoder?.release()
            } catch (_: Throwable) {
            }
            try {
                muxer?.stop()
            } catch (_: Throwable) {
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            videoExtractor.release()
            audioExtractor?.release()
        }
    }

    /**
     * 返回 audioOutputTrack：调用方在视频编码完成后再把音频样本写入同一个 muxer。
     */
    private fun encodeVideo(
        request: VideoCompressRequest,
        videoExtractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        audioFormat: MediaFormat?,
        observer: VideoCompressObserver,
        task: NativeCodecVideoCompressTask,
        startTime: Long
    ): Int? {
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderDone = false
        var muxerStarted = false
        var videoOutputTrack = -1
        var audioOutputTrack = -1
        var lastProgress = 0

        while (!encoderDone && !task.cancelled) {
            if (!decoderInputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    val sampleSize = if (inputBuffer != null) {
                        videoExtractor.readSampleData(inputBuffer, 0)
                    } else {
                        -1
                    }
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        decoderInputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            videoExtractor.sampleTime,
                            videoExtractor.sampleFlags
                        )
                        videoExtractor.advance()
                    }
                }
            }

            while (!decoderOutputDone && !task.cancelled) {
                when (val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val render = decoderInfo.size > 0
                        decoder.releaseOutputBuffer(outputIndex, render)
                        if (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }
            }

            while (!encoderDone && !task.cancelled) {
                when (val outputIndex = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) throw IllegalStateException("Encoder format changed twice")
                        videoOutputTrack = muxer.addTrack(encoder.outputFormat)
                        audioOutputTrack = audioFormat?.let { muxer.addTrack(it) } ?: -1
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("Encoder output buffer is null")
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encoderInfo.size = 0
                        }
                        if (encoderInfo.size > 0) {
                            if (!muxerStarted) throw IllegalStateException("Muxer has not started")
                            encodedData.position(encoderInfo.offset)
                            encodedData.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(videoOutputTrack, encodedData, encoderInfo)
                            val progress = progressFromPresentationTime(
                                request.asset.durationMs,
                                encoderInfo.presentationTimeUs
                            )
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
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }
        return if (muxerStarted) audioOutputTrack else null
    }

    private fun createAudioExtractor(request: VideoCompressRequest): MediaExtractor? {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(request.asset.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return null
            if (selectTrack(extractor, "audio/") >= 0) extractor else null
        } catch (_: Throwable) {
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            null
        }
    }

    private fun copyAudioSamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        task: NativeCodecVideoCompressTask
    ) {
        val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
        val info = MediaCodec.BufferInfo()
        while (!task.cancelled) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            buffer.position(0)
            buffer.limit(info.size)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private fun progressFromPresentationTime(durationMs: Long, presentationTimeUs: Long): Int {
        return if (durationMs > 0L) {
            ((presentationTimeUs / 1000L) * 95L / durationMs).toInt().coerceIn(1, 95)
        } else {
            50
        }
    }

    private fun Int.makeEven(): Int {
        return if (this % 2 == 0) this else this - 1
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 1024 * 1024
        const val OUTPUT_VIDEO_MIME = "video/avc"
        const val DEFAULT_FRAME_RATE = 30
        const val I_FRAME_INTERVAL_SECONDS = 3
        const val TIMEOUT_US = 10_000L
    }
}
