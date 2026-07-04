package com.example.similarscandemo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.clean.similarscan.api.model.ProductCategory
import com.example.similarscandemo.permission.MediaAccessLevel
import com.example.similarscandemo.permission.MediaPermissionHelper
import com.example.similarscandemo.permission.NotificationPermissionHelper
import com.example.similarscandemo.service.MediaScanService
import com.clean.similarscan.api.SimilarScanClient
import com.clean.similarscan.api.SimilarScanSdk
import com.example.similarscandemo.compress.VideoCompressActivity
import com.example.similarscandemo.contacts.ContactsActivity
import com.example.similarscandemo.ui.ProductCategoryAdapter
import com.example.similarscandemo.util.DeleteOperationStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 竞品同构首页：权限申请、扫描进度和纵向媒体分类。
 */
class MainActivity : Activity() {
    private lateinit var scanClient: SimilarScanClient
    private lateinit var scanButton: Button
    private lateinit var compressTabButton: Button
    private lateinit var contactsTabButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var categoryList: ListView
    private var categoryAdapter: ProductCategoryAdapter? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val renderGeneration = AtomicInteger(0)
    private val throttledRender = Runnable { loadAndRenderCachedGroups(updateCachedSummary = false) }
    private var receiverRegistered = false
    private var observerRegistered = false
    private var isScanning = false
    /*
     * 首次安装时会连续出现“媒体权限”和“通知权限”两个系统弹窗。
     * 弹窗切换会触发 Activity onPause/onResume，如果只依赖权限回调直接启动扫描，
     * UI 容易被 onResume 的缓存刷新重置成 Rescan。这里用显式 pending 状态串起权限链路。
     */
    private var pendingScanAfterPermission = false
    private var notificationRequestInFlight = false
    private var notificationPermissionHandled = false
    private var mediaSettingsRequestInFlight = false
    private var scanStartRequestedAt = 0L
    private val mediaChangedScan = Runnable {
        if (!isScanning && MediaPermissionHelper.hasPermission(this)) startScan()
    }
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            val processed = event.getIntExtra(MediaScanService.EXTRA_PROCESSED_COUNT, 0)
            val groups = event.getIntExtra(MediaScanService.EXTRA_GROUP_COUNT, 0)
            val message = event.getStringExtra(MediaScanService.EXTRA_MESSAGE).orEmpty()
            val elapsedTimeText = event.getStringExtra(MediaScanService.EXTRA_ELAPSED_TIME_TEXT).orEmpty()
            when (event.action) {
                MediaScanService.ACTION_PROGRESS -> {
                    isScanning = true
                    statusText.text = message
                    summaryText.text = buildScanSummary(
                        prefix = "Scanning $processed media · $groups groups",
                        elapsedTimeText = elapsedTimeText
                    )
                    scheduleThrottledRender()
                }
                MediaScanService.ACTION_COMPLETE -> finishScanUi(
                    buildScanSummary(
                        prefix = "Reviewed $processed media files",
                        elapsedTimeText = elapsedTimeText
                    ),
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
        pendingScanAfterPermission = savedInstanceState?.getBoolean(STATE_PENDING_SCAN, false) ?: false
        notificationRequestInFlight = savedInstanceState?.getBoolean(STATE_NOTIFICATION_IN_FLIGHT, false) ?: false
        notificationPermissionHandled = savedInstanceState?.getBoolean(STATE_NOTIFICATION_HANDLED, false) ?: false
        mediaSettingsRequestInFlight = savedInstanceState?.getBoolean(STATE_MEDIA_SETTINGS_IN_FLIGHT, false) ?: false
        scanClient = SimilarScanSdk.create(applicationContext)
        if (savedInstanceState == null && DeleteOperationStore.shouldRecover(this)) {
            // 全新进入主页时恢复上次进程遗留的删除状态；Activity 重建时不干扰系统确认。
            scanClient.recoverStaleDeletePending()
            DeleteOperationStore.finish(this)
        }
        scanButton = findViewById(R.id.scanButton)
        compressTabButton = findViewById(R.id.compressTabButton)
        contactsTabButton = findViewById(R.id.contactsTabButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        summaryText = findViewById(R.id.summaryText)
        categoryList = findViewById(R.id.categoryList)

        showCachedResults()
        scanButton.setOnClickListener { handleScanButtonClick() }
        compressTabButton.setOnClickListener {
            startActivity(Intent(this, VideoCompressActivity::class.java))
        }
        contactsTabButton.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_PERMISSION, false)) {
            mainHandler.post { handleScanButtonClick() }
        }
    }

    private fun handleScanButtonClick() {
        when {
            MediaPermissionHelper.hasPermission(this) -> continuePermissionFlowAndScan()
            MediaPermissionHelper.shouldOpenAppSettings(this) -> openMediaPermissionSettings()
            else -> MediaPermissionHelper.request(this)
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
            continuePermissionFlowAndScan()
        } else if (requestCode == NotificationPermissionHelper.REQUEST_CODE) {
            notificationRequestInFlight = false
            notificationPermissionHandled = true
            startPendingScanIfReady()
        } else {
            statusText.text = if (MediaPermissionHelper.shouldOpenAppSettings(this)) {
                "Media access is blocked. Open app settings to grant photos or videos access."
            } else {
                "Photo and video access is required."
            }
            updateScanUiFromState()
        }
    }

    private fun continuePermissionFlowAndScan() {
        pendingScanAfterPermission = true
        updateScanUiFromState()
        if (shouldRequestNotificationBeforeScan() && !notificationRequestInFlight) {
            notificationRequestInFlight = true
            NotificationPermissionHelper.request(this)
        } else {
            startPendingScanIfReady()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::scanClient.isInitialized) {
            if (mediaSettingsRequestInFlight) {
                mediaSettingsRequestInFlight = false
                if (MediaPermissionHelper.hasPermission(this)) {
                    continuePermissionFlowAndScan()
                } else {
                    pendingScanAfterPermission = false
                    showCachedResults()
                }
            } else if (pendingScanAfterPermission) {
                startPendingScanIfReady()
            } else {
                showCachedResults()
            }
            updateScanUiFromState()
        }
    }

    private fun startPendingScanIfReady() {
        if (!pendingScanAfterPermission) return
        if (!MediaPermissionHelper.hasPermission(this)) return
        if (shouldRequestNotificationBeforeScan()) {
            if (!notificationRequestInFlight) {
                notificationRequestInFlight = true
                NotificationPermissionHelper.request(this)
            }
            updateScanUiFromState()
            return
        }
        pendingScanAfterPermission = false
        startScan()
    }

    private fun openMediaPermissionSettings() {
        pendingScanAfterPermission = true
        mediaSettingsRequestInFlight = true
        updateScanUiFromState()
        statusText.text = "Grant photos or videos access in system settings, then return to continue scanning."
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun shouldRequestNotificationBeforeScan(): Boolean {
        return NotificationPermissionHelper.needsRequest(this) && !notificationPermissionHandled
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        scanStartRequestedAt = System.currentTimeMillis()
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

    private fun updateScanUiFromState() {
        /*
         * startForegroundService 到 Service.onStartCommand 之间有一个很短的空窗，
         * 这段时间 MediaScanService.isRunning 可能还是 false，不能立刻把按钮打回 Rescan。
         * 如果服务已结束且没有待启动权限链路，则允许 UI 回到可重新扫描状态。
         */
        val waitingForServiceStart = isScanning &&
            !MediaScanService.isRunning &&
            System.currentTimeMillis() - scanStartRequestedAt < SERVICE_START_GRACE_MS
        isScanning = MediaScanService.isRunning || waitingForServiceStart
        val preparing = pendingScanAfterPermission || notificationRequestInFlight || mediaSettingsRequestInFlight
        scanButton.isEnabled = !isScanning && !preparing
        scanButton.text = when {
            isScanning -> "Scanning..."
            mediaSettingsRequestInFlight -> "Waiting for access..."
            preparing -> "Preparing scan..."
            MediaPermissionHelper.shouldOpenAppSettings(this) -> "Open Settings"
            else -> "Rescan"
        }
        progressBar.visibility = if (isScanning || preparing) View.VISIBLE else View.GONE
        if (preparing && !isScanning) {
            summaryText.text = "Preparing media scan"
            statusText.text = "Finishing permission setup..."
        }
    }

    override fun onStart() {
        super.onStart()
        registerScanReceiver()
        registerMediaObserver()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(mediaChangedScan)
        mainHandler.removeCallbacks(throttledRender)
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

    override fun onDestroy() {
        renderGeneration.incrementAndGet()
        mainHandler.removeCallbacks(throttledRender)
        mainHandler.removeCallbacks(mediaChangedScan)
        if (::scanClient.isInitialized) {
            /*
             * renderExecutor 中可能还有正在读取 SQLite 的首页刷新任务。不能在主线程立即
             * close client，否则后台任务会继续访问已经关闭的数据库并崩溃。把 close 排到
             * 同一个单线程队列末尾，确保已提交读取任务先自然结束。
             */
            runCatching {
                renderExecutor.execute { scanClient.close() }
            }.onFailure {
                scanClient.close()
            }
        }
        renderExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_SCAN, pendingScanAfterPermission)
        outState.putBoolean(STATE_NOTIFICATION_IN_FLIGHT, notificationRequestInFlight)
        outState.putBoolean(STATE_NOTIFICATION_HANDLED, notificationPermissionHandled)
        outState.putBoolean(STATE_MEDIA_SETTINGS_IN_FLIGHT, mediaSettingsRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    private fun showCachedResults() {
        loadAndRenderCachedGroups(updateCachedSummary = true)
        statusText.text = permissionStatusMessage()
    }

    private fun scheduleThrottledRender() {
        mainHandler.removeCallbacks(throttledRender)
        mainHandler.postDelayed(throttledRender, RESULT_RENDER_THROTTLE_MS)
    }

    private fun loadAndRenderCachedGroups(updateCachedSummary: Boolean) {
        val generation = renderGeneration.incrementAndGet()
        renderExecutor.execute {
            if (generation != renderGeneration.get()) return@execute
            val categories = scanClient.loadProductCategories(
                previewAssetLimit = HOME_PREVIEW_ASSET_LIMIT
            )
            mainHandler.post {
                if (generation != renderGeneration.get()) return@post
                render(categories)
                if (updateCachedSummary && categories.any { it.itemCount > 0 } && !isScanning) {
                    summaryText.text = "Cached results are ready while a new scan can update them"
                }
            }
        }
    }

    private fun render(categories: List<ProductCategory>) {
        val adapter = categoryAdapter
        if (adapter == null) {
            categoryAdapter = ProductCategoryAdapter(
                activity = this,
                categories = categories,
                previewAssetLimit = HOME_PREVIEW_ASSET_LIMIT
            )
            categoryList.adapter = categoryAdapter
        } else {
            adapter.submitList(categories)
        }
    }

    private fun finishScanUi(summary: String, message: String) {
        isScanning = false
        progressBar.visibility = View.GONE
        scanButton.isEnabled = true
        scanButton.text = "Rescan"
        summaryText.text = summary
        statusText.text = message
        loadAndRenderCachedGroups(updateCachedSummary = false)
    }

    private fun buildScanSummary(prefix: String, elapsedTimeText: String): String {
        return if (elapsedTimeText.isBlank()) {
            prefix
        } else {
            "$prefix · Time $elapsedTimeText"
        }
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
            MediaAccessLevel.IMAGES_ONLY ->
                "Photo access granted. Videos will not be scanned."
            MediaAccessLevel.VIDEOS_ONLY ->
                "Video access granted. Photos will not be scanned."
            MediaAccessLevel.PARTIAL_VISUAL ->
                "Limited access. Only selected photos and videos can be scanned."
            MediaAccessLevel.FULL_VISUAL, MediaAccessLevel.LEGACY_FULL ->
                "Gallery access granted."
            MediaAccessLevel.NONE ->
                "Allow access to photos and videos to start scanning."
        }
    }

    companion object {
        const val EXTRA_REQUEST_PERMISSION = "request_permission"
        private const val MEDIA_CHANGE_DEBOUNCE_MS = 2_000L
        private const val HOME_PREVIEW_ASSET_LIMIT = 2
        private const val RESULT_RENDER_THROTTLE_MS = 800L
        private const val SERVICE_START_GRACE_MS = 2_000L
        private const val STATE_PENDING_SCAN = "state_pending_scan"
        private const val STATE_NOTIFICATION_IN_FLIGHT = "state_notification_in_flight"
        private const val STATE_NOTIFICATION_HANDLED = "state_notification_handled"
        private const val STATE_MEDIA_SETTINGS_IN_FLIGHT = "state_media_settings_in_flight"
    }
}
