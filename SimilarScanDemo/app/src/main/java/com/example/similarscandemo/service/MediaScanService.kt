package com.example.similarscandemo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.similarscandemo.MainActivity
import com.clean.similarscan.api.SimilarScanObserver
import com.clean.similarscan.api.SimilarScanRequest
import com.clean.similarscan.api.SimilarScanSdk
import com.clean.similarscan.api.VideoFingerprintMode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台媒体扫描服务。
 *
 * 扫描不再依赖 Activity 生命周期，用户切到后台后仍能继续；页面通过包内广播
 * 接收阶段进度并刷新数据库中已经产生的结果。
 */
class MediaScanService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Preparing media scan...", 0))
        if (!scanning.compareAndSet(false, true)) return START_NOT_STICKY
        isRunning = true

        val forceFull = intent?.getBooleanExtra(EXTRA_FORCE_FULL, false) == true
        executor.execute {
            val scanClient = SimilarScanSdk.create(applicationContext)
            try {
                runCatching {
                    scanClient.scan(
                        request = SimilarScanRequest(
                            forceFull = forceFull,
                            videoFingerprintMode = VideoFingerprintMode.COMPETITOR_COMPAT
                        ),
                        observer = SimilarScanObserver { progress ->
                            updateNotification(progress.message, progress.processedCount)
                            sendProgress(
                                ACTION_PROGRESS,
                                progress.processedCount,
                                progress.discoveredGroupCount,
                                progress.message
                            )
                        }
                    )
                }.onSuccess { result ->
                    sendProgress(ACTION_COMPLETE, result.assetCount, result.groups.size, result.message)
                }.onFailure { error ->
                    sendProgress(
                        ACTION_FAILED,
                        0,
                        0,
                        error.message ?: "Media scan failed."
                    )
                }
            } finally {
                // SDK 内部持有 SQLiteOpenHelper，服务扫描结束必须关闭连接。
                scanClient.close()
            }
            scanning.set(false)
            isRunning = false
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun sendProgress(
        action: String,
        processedCount: Int,
        groupCount: Int,
        message: String
    ) {
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .putExtra(EXTRA_PROCESSED_COUNT, processedCount)
                .putExtra(EXTRA_GROUP_COUNT, groupCount)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun updateNotification(message: String, processedCount: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, processedCount))
    }

    private fun notification(message: String, processedCount: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (processedCount > 0) {
            "Scanning media · $processedCount processed"
        } else {
            "Scanning media"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media scanning",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while the local media library is scanned."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PROGRESS = "com.example.similarscandemo.SCAN_PROGRESS"
        const val ACTION_COMPLETE = "com.example.similarscandemo.SCAN_COMPLETE"
        const val ACTION_FAILED = "com.example.similarscandemo.SCAN_FAILED"
        const val EXTRA_FORCE_FULL = "force_full"
        const val EXTRA_PROCESSED_COUNT = "processed_count"
        const val EXTRA_GROUP_COUNT = "group_count"
        const val EXTRA_MESSAGE = "message"
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "media_scan"
        private const val NOTIFICATION_ID = 3001
    }
}
