package com.example.similarscandemo

import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.model.MediaKind
import com.example.similarscandemo.model.ProductCategoryType
import com.example.similarscandemo.scanner.MediaBitmapLoader
import com.example.similarscandemo.scanner.ProductCategoryBuilder
import com.example.similarscandemo.scanner.SimilarMediaScanner
import com.example.similarscandemo.service.MediaScanService
import kotlin.math.abs

/**
 * 大图预览页。
 *
 * 支持按钮和左右滑动两种方式切换上一张 / 下一张，便于在相似结果中快速确认。
 */
class ImagePreviewActivity : Activity() {
    private lateinit var loader: MediaBitmapLoader
    private lateinit var imageView: ImageView
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var scanner: SimilarMediaScanner
    private lateinit var categoryType: ProductCategoryType
    private var groupId: Long = 0L
    private var assets: List<MediaAsset> = emptyList()
    private var currentUri: String = ""
    private var position: Int = 0
    private var downX: Float = 0f
    private var receiverRegistered = false
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaScanService.ACTION_PROGRESS ||
                intent?.action == MediaScanService.ACTION_COMPLETE
            ) {
                reloadLatestAssets()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        loader = MediaBitmapLoader(contentResolver)
        scanner = SimilarMediaScanner(this)
        categoryType = runCatching {
            ProductCategoryType.valueOf(intent.getStringExtra(EXTRA_CATEGORY_TYPE).orEmpty())
        }.getOrNull() ?: run {
            finish()
            return
        }
        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        currentUri = intent.getStringExtra(EXTRA_ASSET_URI).orEmpty()

        imageView = findViewById(R.id.previewImage)
        titleText = findViewById(R.id.previewTitle)
        counterText = findViewById(R.id.previewCounter)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)

        findViewById<TextView>(R.id.previewBackButton).setOnClickListener { finish() }
        previousButton.setOnClickListener { showOffset(-1) }
        nextButton.setOnClickListener { showOffset(1) }
        imageView.setOnTouchListener { _, event -> handleSwipe(event) }
        reloadLatestAssets()
    }

    override fun onStart() {
        super.onStart()
        registerScanReceiver()
    }

    override fun onResume() {
        super.onResume()
        reloadLatestAssets()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun showOffset(offset: Int) {
        val nextPosition = position + offset
        if (nextPosition in assets.indices) {
            position = nextPosition
            currentUri = assets[position].uri.toString()
            showCurrent()
        }
    }

    /**
     * 按分类和数据库 groupId 重新加载预览列表。
     *
     * 如果当前资源已被用户删除，优先停留在原位置附近；如果整组消失则安全关闭页面。
     */
    private fun reloadLatestAssets() {
        val category = ProductCategoryBuilder.build(scanner.loadCachedGroups())
            .first { it.type == categoryType }
        val group = if (groupId > 0L) {
            category.groups.firstOrNull { it.id == groupId }
        } else {
            category.groups.firstOrNull()
        }
        val latest = group?.assets.orEmpty()
        if (latest.isEmpty()) {
            finish()
            return
        }
        val previousPosition = position
        assets = latest
        position = assets.indexOfFirst { it.uri.toString() == currentUri }
            .takeIf { it >= 0 }
            ?: previousPosition.coerceIn(assets.indices)
        currentUri = assets[position].uri.toString()
        showCurrent()
    }

    private fun showCurrent() {
        if (assets.isEmpty()) {
            finish()
            return
        }
        position = position.coerceIn(assets.indices)
        val asset = assets[position]
        titleText.text = asset.name
        counterText.text = "${position + 1} / ${assets.size}"
        previousButton.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
        nextButton.visibility = if (position < assets.lastIndex) View.VISIBLE else View.INVISIBLE

        imageView.setBackgroundColor(Color.BLACK)
        imageView.setImageResource(android.R.color.transparent)
        loader.loadBitmap(asset, 1800)?.let { imageView.setImageBitmap(it) }
    }

    private fun handleSwipe(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                val distance = event.x - downX
                if (abs(distance) > SWIPE_THRESHOLD) {
                    if (distance < 0) showOffset(1) else showOffset(-1)
                }
                return true
            }
        }
        return true
    }

    companion object {
        const val EXTRA_CATEGORY_TYPE = "extra_category_type"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_ASSET_URI = "extra_asset_uri"
        private const val SWIPE_THRESHOLD = 80f
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScanReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(MediaScanService.ACTION_PROGRESS)
            addAction(MediaScanService.ACTION_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }
        receiverRegistered = true
    }
}
