package com.example.similarscandemo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.example.similarscandemo.permission.MediaAccessLevel
import com.example.similarscandemo.permission.MediaPermissionHelper
import com.example.similarscandemo.permission.NotificationPermissionHelper
import com.example.similarscandemo.database.ScanDatabase
import com.example.similarscandemo.scanner.ProductCategoryBuilder
import com.example.similarscandemo.scanner.SimilarMediaScanner
import com.example.similarscandemo.service.MediaScanService
import com.example.similarscandemo.ui.ProductCategoryAdapter
import com.example.similarscandemo.util.DeleteOperationStore

/**
 * 竞品同构首页：权限申请、扫描进度和纵向媒体分类。
 */
class MainActivity : Activity() {
    private lateinit var scanner: SimilarMediaScanner
    private lateinit var scanButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var categoryList: ListView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var observerRegistered = false
    private var isScanning = false
    private val mediaChangedScan = Runnable {
        if (!isScanning && MediaPermissionHelper.hasPermission(this)) startScan()
    }
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            val processed = event.getIntExtra(MediaScanService.EXTRA_PROCESSED_COUNT, 0)
            val groups = event.getIntExtra(MediaScanService.EXTRA_GROUP_COUNT, 0)
            val message = event.getStringExtra(MediaScanService.EXTRA_MESSAGE).orEmpty()
            when (event.action) {
                MediaScanService.ACTION_PROGRESS -> {
                    isScanning = true
                    statusText.text = message
                    summaryText.text = "Scanning $processed media · $groups groups"
                    render(scanner.loadCachedGroups())
                }
                MediaScanService.ACTION_COMPLETE -> finishScanUi(
                    "Reviewed $processed changed media files",
                    message
                )
                MediaScanService.ACTION_FAILED -> finishScanUi("Scan interrupted", message)
            }
        }
    }
    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(mediaChangedScan)
            mainHandler.postDelayed(mediaChangedScan, MEDIA_CHANGE_DEBOUNCE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null && DeleteOperationStore.shouldRecover(this)) {
            // 全新进入主页时恢复上次进程遗留的删除状态；Activity 重建时不干扰系统确认。
            ScanDatabase(applicationContext).recoverStaleDeletePending()
            DeleteOperationStore.finish(this)
        }
        scanner = SimilarMediaScanner(this)
        scanButton = findViewById(R.id.scanButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)
        categoryList = findViewById(R.id.categoryList)

        showCachedResults()
        scanButton.setOnClickListener {
            if (MediaPermissionHelper.hasPermission(this)) requestNotificationThenScan()
            else MediaPermissionHelper.request(this)
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_PERMISSION, false)) {
            mainHandler.post {
                if (MediaPermissionHelper.hasPermission(this)) {
                    requestNotificationThenScan()
                } else {
                    MediaPermissionHelper.request(this)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MediaPermissionHelper.REQUEST_CODE &&
            MediaPermissionHelper.hasPermission(this)
        ) {
            requestNotificationThenScan()
        } else if (requestCode == NotificationPermissionHelper.REQUEST_CODE) {
            startScan()
        } else {
            statusText.text = "Photo and video access is required."
        }
    }

    private fun requestNotificationThenScan() {
        if (NotificationPermissionHelper.needsRequest(this)) {
            NotificationPermissionHelper.request(this)
        } else {
            startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::scanner.isInitialized) {
            showCachedResults()
            isScanning = MediaScanService.isRunning
            scanButton.isEnabled = !isScanning
            scanButton.text = if (isScanning) "Scanning..." else "Rescan"
            progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
        }
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        scanButton.isEnabled = false
        scanButton.text = "Scanning..."
        progressBar.visibility = View.VISIBLE
        statusText.text = permissionStatusMessage()
        val intent = Intent(this, MediaScanService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        registerScanReceiver()
        registerMediaObserver()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(mediaChangedScan)
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        if (observerRegistered) {
            contentResolver.unregisterContentObserver(mediaObserver)
            observerRegistered = false
        }
        super.onStop()
    }

    private fun showCachedResults() {
        val groups = scanner.loadCachedGroups()
        render(groups)
        if (groups.isNotEmpty()) {
            summaryText.text = "Cached results are ready while a new scan can update them"
        }
        statusText.text = permissionStatusMessage()
    }

    private fun render(groups: List<com.example.similarscandemo.model.SimilarGroup>) {
        categoryList.adapter = ProductCategoryAdapter(this, ProductCategoryBuilder.build(groups))
    }

    private fun finishScanUi(summary: String, message: String) {
        isScanning = false
        progressBar.visibility = View.GONE
        scanButton.isEnabled = true
        scanButton.text = "Rescan"
        summaryText.text = summary
        statusText.text = message
        render(scanner.loadCachedGroups())
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScanReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(MediaScanService.ACTION_PROGRESS)
            addAction(MediaScanService.ACTION_COMPLETE)
            addAction(MediaScanService.ACTION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            // API 23-32 没有导出状态参数；广播同时通过 setPackage 限定在本应用。
            registerReceiver(scanReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun registerMediaObserver() {
        if (observerRegistered || !MediaPermissionHelper.hasPermission(this)) return
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        observerRegistered = true
    }

    private fun permissionStatusMessage(): String {
        return when (MediaPermissionHelper.accessLevel(this)) {
            MediaAccessLevel.PARTIAL_VISUAL ->
                "Limited gallery access. Grant full access to scan newly captured media."
            MediaAccessLevel.FULL_VISUAL, MediaAccessLevel.LEGACY_FULL ->
                "Gallery access granted."
            MediaAccessLevel.NONE ->
                "Allow access to photos and videos to start scanning."
        }
    }

    companion object {
        const val EXTRA_REQUEST_PERMISSION = "request_permission"
        private const val MEDIA_CHANGE_DEBOUNCE_MS = 2_000L
    }
}
