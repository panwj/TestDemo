package com.clean.videocompress.internal.engine.media3

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
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
import com.clean.videocompress.internal.util.BitrateCalculator
import java.io.File
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
internal class Media3VideoCompressEngine(
    private val context: Context,
    private val writer: VideoStoreWriter
) : BaseVideoCompressEngine() {
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun compress(
        request: VideoCompressRequest,
        observer: VideoCompressObserver
    ): VideoCompressTask {
        val asset = request.asset
        val startTime = System.currentTimeMillis()
        val outputFile = File(
            context.cacheDir,
            "video_compress/media3_${asset.id}_${System.currentTimeMillis()}.mp4"
        )
        outputFile.parentFile?.mkdirs()
        var transformerRef: Transformer? = null
        val task = Media3VideoCompressTask {
            transformerRef?.cancel()
            outputFile.delete()
            dispatchCancelled(observer, asset.id)
        }
        dispatchStart(observer, asset)
        dispatchProgress(
            observer,
            VideoCompressProgress(asset.id, VideoCompressStage.PREPARING, 0, 0L)
        )
        mainHandler.post {
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(BitrateCalculator.targetBitrate(asset, request.option))
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
                        task.markFinished()
                        saveResult(request, observer, outputFile, startTime)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (task.cancelled) return
                        task.markFinished()
                        outputFile.delete()
                        dispatchFailure(
                            observer,
                            VideoCompressError.EngineFailed(exportException.message, exportException)
                        )
                    }
                })
                .build()
            transformerRef = transformer
            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(asset.uri)).build()
            transformer.start(editedItem, outputFile.absolutePath)
            pollProgress(transformer, task, request, observer, startTime)
        }
        return task
    }

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
                if (!outputFile.exists() || outputFile.length() <= 0L) {
                    dispatchFailure(observer, VideoCompressError.SourceNotFound)
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

    private companion object {
        const val PROGRESS_INTERVAL_MS = 300L
    }
}
