package com.example.similarscandemo.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.clean.videocompress.api.VideoCompressClient
import com.clean.videocompress.api.VideoCompressObserver
import com.clean.videocompress.api.VideoCompressSdk
import com.clean.videocompress.api.VideoCompressTask
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressPermissionOperation
import com.clean.videocompress.api.model.VideoCompressStorageLocation
import com.clean.videocompress.api.model.VideoCompressOption
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.example.similarscandemo.util.FormatUtils
import java.util.ArrayDeque

/**
 * 长视频压缩前台服务。
 *
 * 压缩可能持续较久，放在前台服务中可以降低切后台后被系统中断的风险。
 *
 * 这里由 Demo 业务层维护一个“可追加”的顺序队列：
 * - SDK 仍只负责单个视频的压缩、进度、成功、失败和取消。
 * - ForegroundService 负责把多次点击追加到同一个队列中，并按顺序启动下一条。
 * - 通知栏进度条表示整个队列的总进度，文案中的 Current 表示当前单个视频进度。
 */
class VideoCompressForegroundService : Service() {
    private val pendingRequests = ArrayDeque<VideoCompressRequest>()
    private var client: VideoCompressClient? = null
    private var activeTask: VideoCompressTask? = null
    private var activeRequest: VideoCompressRequest? = null
    private var foregroundStarted = false
    private var cancellingAll = false
    private var lastStartId = 0
    private var totalCount = 0
    private var finishedCount = 0
    private var currentPercent = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            cancelAll(startId)
            return START_NOT_STICKY
        }
        val asset = VideoAssetIntent.get(intent ?: Intent()) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        pendingRequests.add(VideoCompressRequest(asset, readOption(intent)))
        totalCount += 1
        ensureForegroundStarted()
        updateNotification("Queued ${asset.displayName}")
        startNextIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeTask?.cancel()
        client?.close()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, notification("Queued compression task"))
        foregroundStarted = true
    }

    private fun ensureClient(): VideoCompressClient {
        val current = client
        if (current != null) return current
        return VideoCompressSdk.create(applicationContext).also {
            client = it
        }
    }

    private fun startNextIfIdle() {
        if (activeTask != null) return
        val request = pendingRequests.pollFirst() ?: run {
            finishService(lastStartId)
            return
        }
        cancellingAll = false
        activeRequest = request
        currentPercent = 0
        updateNotification("Preparing ${request.asset.displayName}")
        activeTask = ensureClient().compress(
            request,
            object : VideoCompressObserver {
                override fun onStart(asset: CompressVideoAsset) {
                    if (!isActiveAsset(asset.id)) return
                    currentPercent = 0
                    updateNotification("Preparing ${asset.displayName}")
                    sendProgress(ACTION_STARTED, asset, 0, "Preparing compression")
                }

                override fun onProgress(progress: VideoCompressProgress) {
                    val requestAsset = activeRequest?.asset ?: return
                    currentPercent = progress.percent.coerceIn(0, 100)
                    val message = progress.stage.name.lowercase().replace('_', ' ')
                    updateNotification(message)
                    sendProgress(ACTION_PROGRESS, requestAsset, currentPercent, message)
                }

                override fun onSuccess(result: VideoCompressResult) {
                    if (!isActiveAsset(result.sourceAsset.id)) return
                    currentPercent = 100
                    finishedCount += 1
                    updateNotification("Compression complete")
                    sendSuccess(result)
                    completeActiveTask()
                }

                override fun onFailure(error: VideoCompressError) {
                    val requestAsset = activeRequest?.asset ?: return
                    currentPercent = 100
                    finishedCount += 1
                    val message = readableErrorMessage(error)
                    updateNotification(message)
                    sendFailure(requestAsset.id, message)
                    completeActiveTask()
                }

                override fun onCancelled(assetId: Long) {
                    if (cancellingAll || !isActiveAsset(assetId)) return
                    val requestAsset = activeRequest?.asset ?: return
                    currentPercent = 100
                    finishedCount += 1
                    sendFailure(requestAsset.id, "Compression cancelled")
                    completeActiveTask()
                }
            }
        )
    }

    private fun completeActiveTask() {
        activeTask = null
        activeRequest = null
        currentPercent = 0
        startNextIfIdle()
    }

    private fun isActiveAsset(assetId: Long): Boolean {
        return activeRequest?.asset?.id == assetId
    }

    private fun cancelAll(startId: Int) {
        cancellingAll = true
        activeRequest?.asset?.id?.let { sendFailure(it, "Compression cancelled") }
        while (pendingRequests.isNotEmpty()) {
            val pending = pendingRequests.pollFirst() ?: break
            sendFailure(pending.asset.id, "Compression cancelled")
        }
        activeTask?.cancel()
        finishService(startId)
    }

    private fun finishService(startId: Int) {
        pendingRequests.clear()
        activeTask = null
        activeRequest = null
        totalCount = 0
        finishedCount = 0
        currentPercent = 0
        client?.close()
        client = null
        if (foregroundStarted && Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else if (foregroundStarted) {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
        stopSelf(startId)
    }

    private fun readOption(intent: Intent?): VideoCompressOption {
        return VideoCompressOption(
            key = intent?.getStringExtra(EXTRA_OPTION_KEY).orEmpty(),
            title = intent?.getStringExtra(EXTRA_OPTION_TITLE).orEmpty(),
            description = intent?.getStringExtra(EXTRA_OPTION_DESCRIPTION).orEmpty(),
            compressionRatePercent = intent?.getIntExtra(EXTRA_OPTION_RATE, 50) ?: 50
        )
    }

    private fun sendProgress(action: String, asset: CompressVideoAsset, percent: Int, message: String) {
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra(EXTRA_ASSET_ID, asset.id)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun sendSuccess(result: VideoCompressResult) {
        sendBroadcast(
            Intent(ACTION_SUCCESS)
                .setPackage(packageName)
                .putExtra(EXTRA_ASSET_ID, result.sourceAsset.id)
                .putExtra(EXTRA_OUTPUT_URI, result.outputUri.toString())
                .putExtra(EXTRA_OUTPUT_SIZE, result.outputSizeBytes)
                .putExtra(EXTRA_SAVED_BYTES, result.savedBytes)
                .putExtra(EXTRA_ELAPSED_MS, result.elapsedMs)
        )
    }

    private fun sendFailure(assetId: Long, message: String) {
        sendBroadcast(
            Intent(ACTION_FAILED)
                .setPackage(packageName)
                .putExtra(EXTRA_ASSET_ID, assetId)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun readableErrorMessage(error: VideoCompressError): String {
        return when (error) {
            VideoCompressError.Cancelled -> "Compression cancelled"
            VideoCompressError.SdkClosed -> "Compression SDK has been released"
            VideoCompressError.SourceNotFound -> "Source video cannot be opened"
            is VideoCompressError.PermissionDenied -> when (error.operation) {
                VideoCompressPermissionOperation.READ_VIDEO -> "Video permission is required"
                VideoCompressPermissionOperation.SAVE_VIDEO -> "Storage permission is required to save compressed video"
            }
            is VideoCompressError.UnsupportedFormat -> error.reason
            is VideoCompressError.NotWorthCompressing -> error.reason
            is VideoCompressError.InsufficientStorage -> {
                val location = when (error.location) {
                    VideoCompressStorageLocation.TEMP_CACHE -> "temporary cache"
                    VideoCompressStorageLocation.MEDIA_LIBRARY -> "media storage"
                }
                "Not enough $location space. Need ${FormatUtils.formatBytes(error.requiredBytes)}, available ${FormatUtils.formatBytes(error.availableBytes)}"
            }
            is VideoCompressError.EngineFailed -> error.message ?: "Video compression engine failed"
            is VideoCompressError.SaveFailed -> error.message ?: "Compressed video cannot be saved"
            is VideoCompressError.ValidationFailed -> error.reason
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VideoCompressActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VideoCompressForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val currentIndex = currentQueueIndex()
        val queuePercent = queuePercent()
        val title = if (totalCount > 1 && activeRequest != null) {
            "Compressing video $currentIndex of $totalCount"
        } else if (totalCount > 1) {
            "Video compression queue"
        } else {
            "Compressing video"
        }
        val progressText =
            "Finished $finishedCount/$totalCount · Current ${currentPercent.coerceIn(0, 100)}% · Queue $queuePercent%"
        val contentText = "$message · $progressText"
        val cancelAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Cancel",
            cancelIntent
        ).build()
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(contentText))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, queuePercent, totalCount <= 0)
            .addAction(cancelAction)
            .build()
    }

    private fun currentQueueIndex(): Int {
        if (totalCount <= 0) return 0
        return (finishedCount + 1).coerceAtMost(totalCount)
    }

    private fun queuePercent(): Int {
        if (totalCount <= 0) return 0
        val activeProgress = if (activeRequest == null) 0 else currentPercent.coerceIn(0, 100)
        return ((finishedCount * 100 + activeProgress) / totalCount).coerceIn(0, 100)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video compression",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while videos are compressed."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STARTED = "com.example.similarscandemo.COMPRESS_STARTED"
        const val ACTION_PROGRESS = "com.example.similarscandemo.COMPRESS_PROGRESS"
        const val ACTION_SUCCESS = "com.example.similarscandemo.COMPRESS_SUCCESS"
        const val ACTION_FAILED = "com.example.similarscandemo.COMPRESS_FAILED"
        const val ACTION_CANCEL = "com.example.similarscandemo.COMPRESS_CANCEL"

        const val EXTRA_OPTION_KEY = "option_key"
        const val EXTRA_OPTION_TITLE = "option_title"
        const val EXTRA_OPTION_DESCRIPTION = "option_description"
        const val EXTRA_OPTION_RATE = "option_rate"
        const val EXTRA_ASSET_ID = "asset_id"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_OUTPUT_SIZE = "output_size"
        const val EXTRA_SAVED_BYTES = "saved_bytes"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"

        private const val CHANNEL_ID = "video_compress"
        private const val NOTIFICATION_ID = 4001
    }
}
