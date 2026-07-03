package com.example.similarscandemo

import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabaseLockedException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.ProductCategory
import com.clean.similarscan.api.model.ProductCategoryType
import com.clean.similarscan.api.model.SimilarGroup
import com.example.similarscandemo.service.MediaScanService
import com.clean.similarscan.api.SimilarScanClient
import com.clean.similarscan.api.SimilarScanSdk
import com.example.similarscandemo.ui.GroupAdapter
import com.example.similarscandemo.ui.GridAdapter
import com.example.similarscandemo.ui.MediaDisplaySorter
import com.example.similarscandemo.util.FormatUtils
import com.example.similarscandemo.util.DeleteOperationStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 竞品同构分类详情。
 *
 * 相似类默认保留每组质量最好的资源，并选中其余资源；用户可调整选择，
 * 再通过系统媒体删除确认完成实际清理。
 */
class GroupDetailActivity : Activity() {
    private lateinit var category: ProductCategory
    private lateinit var categoryType: ProductCategoryType
    private lateinit var scanClient: SimilarScanClient
    private lateinit var groupRecycler: RecyclerView
    private lateinit var selectAllButton: TextView
    private lateinit var sortButton: TextView
    private lateinit var deleteButton: Button
    private var groupAdapter: GroupAdapter? = null
    private var gridAdapter: GridAdapter? = null
    private val selectedUris = linkedSetOf<String>()
    private val bestUris = linkedSetOf<String>()
    private val pendingDeleteUris = linkedSetOf<String>()
    private var selectionInitialized = false
    private var receiverRegistered = false
    private var gridLayoutInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadExecutor = Executors.newSingleThreadExecutor()
    private val reloadGeneration = AtomicInteger(0)
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaScanService.ACTION_PROGRESS ||
                intent?.action == MediaScanService.ACTION_COMPLETE
            ) {
                reloadLatestCategory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_detail)

        categoryType = runCatching {
            ProductCategoryType.valueOf(intent.getStringExtra(EXTRA_CATEGORY_TYPE).orEmpty())
        }.getOrNull() ?: run {
            finish()
            return
        }
        scanClient = SimilarScanSdk.create(applicationContext)
        pendingDeleteUris.addAll(
            savedInstanceState?.getStringArrayList(STATE_PENDING_DELETE_URIS).orEmpty()
        )
        groupRecycler = findViewById(R.id.detailGroupRecycler)
        selectAllButton = findViewById(R.id.selectAllButton)
        sortButton = findViewById(R.id.sortButton)
        deleteButton = findViewById(R.id.deleteButton)

        findViewById<TextView>(R.id.detailTitle).text = categoryType.title
        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }

        groupRecycler.layoutManager = LinearLayoutManager(this)

        selectAllButton.setOnClickListener { toggleSelectAll() }
        sortButton.text = "Newest"
        sortButton.visibility = View.GONE
        deleteButton.setOnClickListener { requestDeleteSelected() }
        deleteButton.isEnabled = false
        deleteButton.alpha = 0.45f
        reloadLatestCategory()
    }

    override fun onStart() {
        super.onStart()
        registerScanReceiver()
    }

    override fun onResume() {
        super.onResume()
        reloadLatestCategory()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        reloadGeneration.incrementAndGet()
        reloadExecutor.shutdownNow()
        if (::scanClient.isInitialized) {
            scanClient.close()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(
            STATE_PENDING_DELETE_URIS,
            ArrayList(pendingDeleteUris)
        )
        super.onSaveInstanceState(outState)
    }

    /**
     * 从数据库重新生成当前分类，避免详情页长期持有首页传来的旧快照。
     *
     * 已有选择按 URI 与最新 ACTIVE 资源求交集。扫描新增资源会展示出来，但不会突然
     * 改变用户正在进行的选择；首次进入时才应用竞品默认“保留 Best、选中其余项”。
     */
    private fun reloadLatestCategory() {
        val generation = reloadGeneration.incrementAndGet()
        reloadExecutor.execute {
            val latestCategory = loadLatestCategoryWithRetry() ?: return@execute

            mainHandler.post {
                if (generation != reloadGeneration.get() || isFinishing || isDestroyed) return@post
                applyLatestCategory(latestCategory)
            }
        }
    }

    private fun loadLatestCategoryWithRetry(): ProductCategory? {
        repeat(DB_READ_RETRY_COUNT) { attempt ->
            try {
                return scanClient.loadProductCategory(categoryType)
            } catch (_: SQLiteDatabaseLockedException) {
                Thread.sleep(DB_READ_RETRY_DELAY_MS * (attempt + 1))
            } catch (_: IllegalStateException) {
                Thread.sleep(DB_READ_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return runCatching {
            scanClient.loadProductCategory(categoryType)
        }.getOrNull()
    }

    private fun applyLatestCategory(latestCategory: ProductCategory) {
        category = latestCategory
        val activeUris = category.assets.mapTo(hashSetOf()) { it.uri.toString() }
        selectedUris.retainAll(activeUris)
        bestUris.clear()
        category.groups.forEach { group ->
            bestAsset(group.assets)?.let { bestUris += it.uri.toString() }
        }
        if (!selectionInitialized) {
            initializeRecommendedSelection()
            selectionInitialized = true
        }
        findViewById<TextView>(R.id.detailSubtitle).text =
            "${category.itemCount} items · ${FormatUtils.formatBytes(category.totalSize)}"
        renderContent()
    }

    private fun initializeRecommendedSelection() {
        if (!category.type.grouped) return
        category.groups.forEach { group ->
            val best = bestAsset(group.assets) ?: return@forEach
            bestUris += best.uri.toString()
            group.assets
                .filterNot { it.uri == best.uri }
                .forEach { selectedUris += it.uri.toString() }
        }
    }

    private fun bestAsset(assets: List<MediaAsset>): MediaAsset? {
        return assets.maxWithOrNull(
            compareBy<MediaAsset>(
                { it.qualityScore },
                { it.isFavorite },
                { it.isEdited },
                { it.width.toLong() * it.height.toLong() },
                { it.size },
                { it.createdAt.time }
            )
        )
    }

    private fun renderContent() {
        if (category.type.grouped) {
            val displayedGroups = sortedGroups()
            if (groupAdapter == null) {
                groupRecycler.layoutManager = LinearLayoutManager(this)
                groupRecycler.itemAnimator = null
                groupAdapter = GroupAdapter(
                    activity = this,
                    selectedUris = selectedUris,
                    bestUris = bestUris,
                    onAssetClick = { group, position ->
                        openPreview(group, position)
                    },
                    onAssetSelect = { group, position ->
                        toggleAsset(group.assets[position])
                    }
                )
                groupRecycler.adapter = groupAdapter
            }
            groupRecycler.visibility = View.VISIBLE
            groupAdapter?.submitList(displayedGroups)
        } else {
            val sortedAssets = MediaDisplaySorter.newestFirst(category.assets)
            if (!gridLayoutInitialized) {
                val spacing = (8 * resources.displayMetrics.density).toInt()
                groupRecycler.layoutManager = GridLayoutManager(this, 2)
                groupRecycler.addItemDecoration(GridAdapter.GridSpacingItemDecoration(spacing))
                groupRecycler.itemAnimator = null
                gridLayoutInitialized = true
            }
            groupRecycler.visibility = View.VISIBLE
            if (gridAdapter == null) {
                gridAdapter = GridAdapter(
                    activity = this,
                    selectedUris = selectedUris,
                    onItemClick = { asset ->
                        val currentAssets = MediaDisplaySorter.newestFirst(category.assets)
                        openPreviewFromFlatList(
                            currentAssets,
                            currentAssets.indexOfFirst { it.uri == asset.uri }
                        )
                    },
                    onSelectionToggle = { asset, position ->
                        toggleAsset(asset, position)
                    }
                )
                groupRecycler.adapter = gridAdapter
            }
            gridAdapter?.submitList(sortedAssets)
        }
        updateSelectionControls()
    }

    private fun sortedGroups(): List<SimilarGroup> {
        return MediaDisplaySorter.newestGroupFirst(category.groups)
    }

    private fun toggleAsset(asset: MediaAsset, position: Int = -1) {
        val uri = asset.uri.toString()
        if (!selectedUris.add(uri)) selectedUris.remove(uri)
        if (category.type.grouped) {
            groupAdapter?.updateSelection()
        } else {
            if (position >= 0) {
                gridAdapter?.notifySelectionChanged(position)
            } else {
                gridAdapter?.notifySelectionChanged(0)
            }
        }
        updateSelectionControls()
    }

    private fun toggleSelectAll() {
        if (!::category.isInitialized) return
        val allUris = category.assets.map { it.uri.toString() }
        if (selectedUris.size == allUris.size) selectedUris.clear()
        else selectedUris.addAll(allUris)
        renderContent()
    }

    private fun updateSelectionControls() {
        if (!::category.isInitialized) return
        val selectedAssets = category.assets.filter { selectedUris.contains(it.uri.toString()) }
        val selectedSize = selectedAssets.sumOf { it.size }
        selectAllButton.text =
            if (selectedUris.size == category.assets.size && category.assets.isNotEmpty()) {
                "Deselect All"
            } else {
                "Select All"
            }
        deleteButton.isEnabled = selectedAssets.isNotEmpty()
        deleteButton.alpha = if (selectedAssets.isEmpty()) 0.45f else 1f
        deleteButton.text = if (selectedAssets.isEmpty()) {
            "Select media to delete"
        } else {
            "Delete ${selectedAssets.size} · Save ${FormatUtils.formatBytes(selectedSize)}"
        }
    }

    private fun requestDeleteSelected() {
        if (!::category.isInitialized) return
        val marked = scanClient.markDeletePending(selectedUris)
        pendingDeleteUris.clear()
        pendingDeleteUris.addAll(marked)
        val uris = pendingDeleteUris.map(Uri::parse)
        if (uris.isEmpty()) return
        DeleteOperationStore.begin(this)
        reloadLatestCategory()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val request = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(
                    request.intentSender,
                    DELETE_REQUEST_CODE,
                    null,
                    0,
                    0,
                    0
                )
            }.onFailure {
                cancelDeletion()
            }
        } else {
            val allDeleted = uris.all {
                runCatching { contentResolver.delete(it, null, null) > 0 }.getOrDefault(false)
            }
            if (allDeleted) completeDeletion() else cancelDeletion()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DELETE_REQUEST_CODE && resultCode == RESULT_OK) {
            completeDeletion()
        } else if (requestCode == DELETE_REQUEST_CODE) {
            cancelDeletion()
        }
    }

    private fun completeDeletion() {
        scanClient.finalizeDelete(pendingDeleteUris)
        DeleteOperationStore.finish(this)
        selectedUris.removeAll(pendingDeleteUris)
        pendingDeleteUris.clear()
        setResult(RESULT_OK)
        reloadLatestCategory()
    }

    private fun cancelDeletion() {
        scanClient.restoreDeletePending(pendingDeleteUris)
        DeleteOperationStore.finish(this)
        pendingDeleteUris.clear()
        reloadLatestCategory()
    }

    private fun openPreview(group: SimilarGroup, position: Int) {
        val asset = group.assets.getOrNull(position) ?: return
        if (asset.kind == com.clean.similarscan.api.model.MediaKind.VIDEO || 
            asset.kind == com.clean.similarscan.api.model.MediaKind.SCREEN_RECORDING) {
            playVideo(asset.uri)
        } else {
            startActivity(
                Intent(this, ImagePreviewActivity::class.java)
                    .putExtra(ImagePreviewActivity.EXTRA_CATEGORY_TYPE, category.type.name)
                    .putExtra(ImagePreviewActivity.EXTRA_GROUP_ID, group.id)
                    .putExtra(ImagePreviewActivity.EXTRA_ASSET_URI, asset.uri.toString())
            )
        }
    }

    private fun playVideo(uri: android.net.Uri) {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, uri.toString())
        )
    }

    private fun openPreviewFromFlatList(assets: List<MediaAsset>, position: Int) {
        if (position !in assets.indices) return
        val tempGroup = SimilarGroup(
            id = 0,
            title = categoryType.title,
            subtitle = "",
            category = com.clean.similarscan.api.model.GroupCategory.SIMILAR,
            kind = com.clean.similarscan.api.model.MediaKind.PHOTO,
            assets = assets
        )
        openPreview(tempGroup, position)
    }

    companion object {
        const val EXTRA_CATEGORY_TYPE = "extra_category_type"
        private const val DELETE_REQUEST_CODE = 2002
        private const val STATE_PENDING_DELETE_URIS = "pending_delete_uris"
        private const val DB_READ_RETRY_COUNT = 5
        private const val DB_READ_RETRY_DELAY_MS = 80L
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
