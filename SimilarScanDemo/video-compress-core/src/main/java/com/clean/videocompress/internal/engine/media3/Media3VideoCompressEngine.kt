package com.clean.videocompress.internal.engine.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.clean.videocompress.api.model.VideoCompressStage
import com.clean.videocompress.internal.engine.BaseVideoCompressEngine
import com.clean.videocompress.internal.media.VideoStoreWriter
import com.clean.videocompress.internal.policy.Media3CompressionPlan
import com.clean.videocompress.internal.policy.Media3CompressionPolicy
import com.clean.videocompress.internal.policy.VideoFormatInspector
import com.clean.videocompress.internal.util.StorageSpaceChecker
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 Transformer 压缩引擎。
 *
 * 这是 SDK 默认生产方案：先生成压缩计划，再由 Media3 转成 H.264 MP4，
 * 最后保存到系统媒体库。
 */
@OptIn(UnstableApi::class)
internal class Media3VideoCompressEngine(
    private val context: Context,
    private val writer: VideoStoreWriter
) : BaseVideoCompressEngine() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val formatInspector = VideoFormatInspector(context)
    private val closed = AtomicBoolean(false)
    private val activeTasks = Collections.synchronizedSet(mutableSetOf<Media3VideoCompressTask>())

    /**
     * 执行单个视频压缩。
     *
     * 该方法会完成：格式探测、策略计划、空间检查、Media3 转码、结果保存。
     */
    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        val asset = request.asset
        if (closed.get()) {
            dispatchFailure(observer, VideoCompressError.SdkClosed)
            return Media3VideoCompressTask(onCancel = {})
        }
        val startTime = System.currentTimeMillis()
        val profile = formatInspector.inspect(asset)
        val plan = Media3CompressionPolicy.buildPlan(asset, request.option, profile)
        plan.rejectReason?.let { reason ->
            dispatchFailure(observer, VideoCompressError.NotWorthCompressing(reason))
            return Media3VideoCompressTask(onCancel = {})
        }
        StorageSpaceChecker.checkBeforeCompress(
            context = context,
            estimatedOutputBytes = plan.estimatedOutputSizeBytes,
            sourceSizeBytes = asset.sizeBytes
        )?.let { error ->
            dispatchFailure(observer, error)
            return Media3VideoCompressTask(onCancel = {})
        }
        val outputFile = File(
            context.cacheDir,
            "video_compress/media3_${asset.id}_${System.currentTimeMillis()}.mp4"
        )
        outputFile.parentFile?.mkdirs()
        var transformerRef: Transformer? = null
        lateinit var task: Media3VideoCompressTask
        task = Media3VideoCompressTask {
            transformerRef?.cancel()
            outputFile.delete()
            activeTasks.remove(task)
            dispatchCancelled(observer, asset.id)
        }
        activeTasks += task
        dispatchStart(observer, asset)
        dispatchProgress(
            observer,
            VideoCompressProgress(asset.id, VideoCompressStage.PREPARING, 0, 0L)
        )
        mainHandler.post {
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(plan.targetBitrate)
                        .setiFrameIntervalSeconds(3f)
                        .build()
                )
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (task.cancelled) return
                        finishTask(task)
                        saveResult(request, observer, outputFile, startTime)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (task.cancelled) return
                        finishTask(task)
                        outputFile.delete()
                        val message = if (plan.isHevcSource) {
                            "HEVC to H.264 compression failed: ${exportException.message}"
                        } else {
                            exportException.message
                        }
                        dispatchFailure(
                            observer,
                            VideoCompressError.EngineFailed(message, exportException)
                        )
                    }
                })
                .build()
            transformerRef = transformer
            val editedItem = buildEditedItem(asset.uri, plan)
            transformer.start(editedItem, outputFile.absolutePath)
            pollProgress(transformer, task, request, observer, startTime)
        }
        return task
    }

    /**
     * 关闭引擎并取消正在运行的任务。
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeTasks.toList().forEach { it.cancel() }
            activeTasks.clear()
            mainHandler.removeCallbacksAndMessages(null)
            ioExecutor.shutdownNow()
        }
    }

    /**
     * 构建 Media3 输入项。
     *
     * 如果压缩计划要求降分辨率，会通过 Presentation effect 处理。
     */
    private fun buildEditedItem(uri: android.net.Uri, plan: Media3CompressionPlan): EditedMediaItem {
        val builder = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        plan.targetHeight?.let { height ->
            builder.setEffects(
                androidx.media3.transformer.Effects(
                    emptyList(),
                    listOf(Presentation.createForHeight(height))
                )
            )
        }
        return builder.build()
    }

    /**
     * 轮询 Media3 转码进度。
     */
    private fun pollProgress(
        transformer: Transformer,
        task: Media3VideoCompressTask,
        request: VideoCompressRequest,
        observer: VideoCompressObserver,
        startTime: Long
    ) {
        if (task.cancelled || task.finished) return
        val holder = ProgressHolder()
        val state = transformer.getProgress(holder)
        if (state != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) {
            val percent = holder.progress.coerceIn(0, 99)
            dispatchProgress(
                observer,
                VideoCompressProgress(
                    assetId = request.asset.id,
                    stage = VideoCompressStage.TRANSCODING,
                    percent = percent,
                    elapsedMs = System.currentTimeMillis() - startTime
                )
            )
        }
        if (state != Transformer.PROGRESS_STATE_NOT_STARTED && state != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) {
            mainHandler.postDelayed({
                pollProgress(transformer, task, request, observer, startTime)
            }, PROGRESS_INTERVAL_MS)
        }
    }

    /**
     * 保存转码结果。
     *
     * 保存前会做基础校验，保存完成后删除 cache 临时文件。
     */
    private fun saveResult(
        request: VideoCompressRequest,
        observer: VideoCompressObserver,
        outputFile: File,
        startTime: Long
    ) {
        dispatchProgress(
            observer,
            VideoCompressProgress(
                assetId = request.asset.id,
                stage = VideoCompressStage.SAVING_TO_MEDIASTORE,
                percent = 99,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        )
        ioExecutor.execute {
            try {
                val validationReason = Media3CompressionPolicy.validateResult(request.asset, outputFile)
                if (validationReason != null) {
                    dispatchFailure(observer, VideoCompressError.ValidationFailed(validationReason))
                    return@execute
                }
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
                dispatchFailure(observer, VideoCompressError.SaveFailed(error.message, error))
            } finally {
                outputFile.delete()
            }
        }
    }

    /**
     * 标记任务结束并从活动任务集合移除。
     */
    private fun finishTask(task: Media3VideoCompressTask) {
        task.markFinished()
        activeTasks.remove(task)
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 300L
    }
}
