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
    private val flatAssets = mutableListOf<MediaAsset>()
    private val knownAssetsByUri = linkedMapOf<String, MediaAsset>()
    private val loadingGroupIds = hashSetOf<Long>()
    private var selectionInitialized = false
    private var receiverRegistered = false
    private var gridLayoutInitialized = false
    private var flatAssetsLoading = false
    private var flatAssetsLoadedAll = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reloadExecutor = Executors.newSingleThreadExecutor()
    private val reloadGeneration = AtomicInteger(0)
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val resultUpdated = intent?.getBooleanExtra(MediaScanService.EXTRA_RESULT_UPDATED, false) == true
            if ((action == MediaScanService.ACTION_PROGRESS && resultUpdated) ||
                action == MediaScanService.ACTION_COMPLETE
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
        if (::scanClient.isInitialized) {
            /*
             * 详情页的分类刷新/分页加载都在 reloadExecutor 中读 SQLite。将 close 排到队列
             * 末尾，避免仍在执行的分页查询访问已经关闭的数据库。
             */
            runCatching {
                reloadExecutor.execute { scanClient.close() }
            }.onFailure {
                scanClient.close()
            }
        }
        reloadExecutor.shutdown()
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
        val previewAssetLimit = if (categoryType.grouped) GROUP_ASSET_PAGE_SIZE else 0
        repeat(DB_READ_RETRY_COUNT) { attempt ->
            try {
                return loadLatestCategory(previewAssetLimit)
            } catch (_: SQLiteDatabaseLockedException) {
                Thread.sleep(DB_READ_RETRY_DELAY_MS * (attempt + 1))
            } catch (_: IllegalStateException) {
                Thread.sleep(DB_READ_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return runCatching {
            loadLatestCategory(previewAssetLimit)
        }.getOrNull()
    }

    private fun loadLatestCategory(previewAssetLimit: Int): ProductCategory? {
        if (MediaScanService.isRunning) {
            scanClient.loadProgressiveProductCategory(categoryType, previewAssetLimit)
                ?.takeIf { it.itemCount > 0 }
                ?.let { return it }
        }
        return scanClient.loadProductCategory(categoryType, previewAssetLimit)
    }

    private fun applyLatestCategory(latestCategory: ProductCategory) {
        category = latestCategory
        if (category.type.grouped) {
            val activeUris = category.assets.mapTo(hashSetOf()) { it.uri.toString() }
            selectedUris.retainAll(activeUris)
        } else {
            flatAssets.clear()
            knownAssetsByUri.clear()
            flatAssetsLoading = false
            flatAssetsLoadedAll = category.itemCount == 0
        }
        loadingGroupIds.clear()
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
        if (!category.type.grouped) {
            loadNextFlatAssetPage(reset = true)
        }
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
                    },
                    onGroupLoadMore = { group ->
                        loadNextGroupAssetPage(group)
                    }
                )
                groupRecycler.adapter = groupAdapter
            }
            groupRecycler.visibility = View.VISIBLE
            groupAdapter?.submitList(displayedGroups)
        } else {
            if (!gridLayoutInitialized) {
                val spacing = (8 * resources.displayMetrics.density).toInt()
                groupRecycler.layoutManager = GridLayoutManager(this, 2).also { layoutManager ->
                    groupRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            if (dy <= 0 || category.type.grouped) return
                            val lastVisible = layoutManager.findLastVisibleItemPosition()
                            if (lastVisible >= flatAssets.size - LOAD_MORE_THRESHOLD) {
                                loadNextFlatAssetPage(reset = false)
                            }
                        }
                    })
                }
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
                        val currentAssets = flatAssets.toList()
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
            gridAdapter?.submitList(flatAssets.toList())
        }
        updateSelectionControls()
    }

    /**
     * 相似/重复分组按 groupId 分页追加横向资源。
     *
     * 这只改变详情页读取方式，不改变 SDK 已经生成的相似组关系。
     */
    private fun loadNextGroupAssetPage(group: SimilarGroup) {
        if (!::category.isInitialized || !category.type.grouped) return
        if (group.id <= 0L || group.assets.size >= group.totalAssetCount) return
        if (!loadingGroupIds.add(group.id)) return
        val generation = reloadGeneration.get()
        val offset = group.assets.size
        runCatching {
            reloadExecutor.execute {
                val page = runCatching {
                    scanClient.loadSimilarGroupAssets(
                        groupId = group.id,
                        offset = offset,
                        limit = GROUP_ASSET_PAGE_SIZE
                    )
                }.getOrDefault(emptyList())
                mainHandler.post {
                    loadingGroupIds.remove(group.id)
                    if (generation != reloadGeneration.get() || isFinishing || isDestroyed) return@post
                    if (page.isEmpty()) return@post
                    val updatedGroups = category.groups.map { current ->
                        if (current.id != group.id) return@map current
                        val mergedAssets = (current.assets + page).distinctBy { it.uri }
                        if (selectionInitialized) {
                            val bestUri = bestUris.firstOrNull { best ->
                                mergedAssets.any { it.uri.toString() == best }
                            }
                            page.filterNot { it.uri.toString() == bestUri }
                                .forEach { selectedUris += it.uri.toString() }
                        }
                        current.copy(assets = MediaDisplaySorter.newestFirst(mergedAssets))
                    }
                    category = category.copy(groups = updatedGroups)
                    groupAdapter?.submitList(sortedGroups())
                    updateSelectionControls()
                }
            }
        }.onFailure {
            loadingGroupIds.remove(group.id)
        }
    }

    /**
     * 平铺类分类按页加载资源，避免 Other 等大分类详情页一次性把全部资源放入内存。
     *
     * category 中保留完整数量/大小统计，flatAssets 只保存当前已经加载到 UI 的页面数据。
     */
    private fun loadNextFlatAssetPage(reset: Boolean) {
        if (!::category.isInitialized || category.type.grouped) return
        if (flatAssetsLoading || flatAssetsLoadedAll) return
        val generation = reloadGeneration.get()
        val offset = if (reset) 0 else flatAssets.size
        flatAssetsLoading = true
        runCatching {
            reloadExecutor.execute {
                val page = runCatching {
                    scanClient.loadProductCategoryAssets(
                        type = categoryType,
                        offset = offset,
                        limit = DETAIL_ASSET_PAGE_SIZE
                    )
                }.getOrDefault(emptyList())
                mainHandler.post {
                    if (generation != reloadGeneration.get() || isFinishing || isDestroyed) return@post
                    if (reset) {
                        flatAssets.clear()
                        knownAssetsByUri.clear()
                    }
                    page.forEach { asset ->
                        val key = asset.uri.toString()
                        if (!knownAssetsByUri.containsKey(key)) {
                            flatAssets += asset
                        }
                        knownAssetsByUri[key] = asset
                    }
                    val activeLoadedUris = knownAssetsByUri.keys
                    selectedUris.retainAll(activeLoadedUris)
                    flatAssetsLoadedAll = page.size < DETAIL_ASSET_PAGE_SIZE ||
                        flatAssets.size >= category.itemCount
                    flatAssetsLoading = false
                    gridAdapter?.submitList(flatAssets.toList())
                    updateSelectionControls()
                }
            }
        }.onFailure {
            flatAssetsLoading = false
        }
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
        val selectableAssets = currentSelectableAssets()
        val allUris = selectableAssets.map { it.uri.toString() }
        if (selectedUris.size == allUris.size) selectedUris.clear()
        else selectedUris.addAll(allUris)
        renderContent()
    }

    private fun updateSelectionControls() {
        if (!::category.isInitialized) return
        val selectableAssets = currentSelectableAssets()
        val selectedAssets = selectableAssets.filter { selectedUris.contains(it.uri.toString()) }
        val selectedSize = selectedAssets.sumOf { it.size }
        selectAllButton.text =
            if (selectedUris.size == selectableAssets.size && selectableAssets.isNotEmpty()) {
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

    private fun currentSelectableAssets(): List<MediaAsset> {
        return if (category.type.grouped) category.assets else flatAssets
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
        private const val DETAIL_ASSET_PAGE_SIZE = 120
        private const val GROUP_ASSET_PAGE_SIZE = 60
        private const val LOAD_MORE_THRESHOLD = 12
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
