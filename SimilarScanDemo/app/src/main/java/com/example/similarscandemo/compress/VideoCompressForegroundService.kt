package com.example.similarscandemo.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.clean.videocompress.api.VideoCompressQueueFailure
import com.clean.videocompress.api.VideoCompressQueueObserver
import com.clean.videocompress.api.VideoCompressQueueTask
import com.clean.videocompress.api.VideoCompressClient
import com.clean.videocompress.api.VideoCompressSdk
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressPermissionOperation
import com.clean.videocompress.api.model.VideoCompressStorageLocation
import com.clean.videocompress.api.model.VideoCompressOption
import com.clean.videocompress.api.model.VideoCompressProgress
import com.clean.videocompress.api.model.VideoCompressRequest
import com.clean.videocompress.api.model.VideoCompressResult
import com.example.similarscandemo.util.FormatUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长视频压缩前台服务。
 *
 * 压缩可能持续较久，放在前台服务中可以降低切后台后被系统中断的风险。
 * 服务本身只承载 Demo 业务通知，真正的任务队列由 video-compress-core 提供。
 */
class VideoCompressForegroundService : Service() {
    private val running = AtomicBoolean(false)
    private var client: VideoCompressClient? = null
    private var queueTask: VideoCompressQueueTask? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            queueTask?.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        val asset = VideoAssetIntent.get(intent ?: Intent()) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val option = readOption(intent)
        startForeground(NOTIFICATION_ID, notification("Preparing compression", 0, 0, 1))
        client = VideoCompressSdk.create(applicationContext)
        queueTask = client?.compressQueue(
            listOf(VideoCompressRequest(asset, option)),
            object : VideoCompressQueueObserver {
                override fun onQueueStart(totalCount: Int) {
                    sendProgress(ACTION_STARTED, asset, 0, "Preparing compression")
                }

                override fun onItemStart(index: Int, totalCount: Int, asset: CompressVideoAsset) {
                    updateNotification("Compressing ${asset.displayName}", 0, index, totalCount)
                    sendProgress(ACTION_PROGRESS, asset, 0, "Preparing compression")
                }

                override fun onItemProgress(index: Int, totalCount: Int, progress: VideoCompressProgress) {
                    val message = progress.stage.name.lowercase().replace('_', ' ')
                    updateNotification(message, progress.percent, index, totalCount)
                    sendProgress(ACTION_PROGRESS, asset, progress.percent, message)
                }

                override fun onItemSuccess(index: Int, totalCount: Int, result: VideoCompressResult) {
                    updateNotification("Compression complete", 100, index, totalCount)
                    sendSuccess(result)
                }

                override fun onItemFailure(
                    index: Int,
                    totalCount: Int,
                    request: VideoCompressRequest,
                    error: VideoCompressError
                ) {
                    val message = readableErrorMessage(error)
                    updateNotification(message, 100, index, totalCount)
                    sendFailure(request.asset.id, message)
                }

                override fun onQueueComplete(
                    results: List<VideoCompressResult>,
                    failures: List<VideoCompressQueueFailure>
                ) {
                    finishService(startId)
                }

                override fun onQueueCancelled(
                    results: List<VideoCompressResult>,
                    failures: List<VideoCompressQueueFailure>
                ) {
                    sendFailure(asset.id, "Compression cancelled")
                    finishService(startId)
                }
            }
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        queueTask?.cancel()
        client?.close()
        client = null
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishService(startId: Int) {
        running.set(false)
        client?.close()
        client = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
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

    private fun updateNotification(message: String, percent: Int, index: Int, total: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, percent, index, total))
    }

    private fun notification(message: String, percent: Int, index: Int, total: Int): Notification {
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
        val title = if (total > 1) {
            "Compressing video $index of $total"
        } else {
            "Compressing video"
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText("$message · $percent%")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
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
