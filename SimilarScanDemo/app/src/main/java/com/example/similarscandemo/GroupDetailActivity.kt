package com.example.similarscandemo

import android.app.Activity
import android.app.AlertDialog
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.similarscandemo.database.ScanDatabase
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.model.ProductCategory
import com.example.similarscandemo.model.ProductCategoryType
import com.example.similarscandemo.model.SimilarGroup
import com.example.similarscandemo.scanner.ProductCategoryBuilder
import com.example.similarscandemo.scanner.SimilarMediaScanner
import com.example.similarscandemo.service.MediaScanService
import com.example.similarscandemo.ui.GroupAdapter
import com.example.similarscandemo.ui.GridAdapter
import com.example.similarscandemo.util.FormatUtils
import com.example.similarscandemo.util.DeleteOperationStore

/**
 * 竞品同构分类详情。
 *
 * 相似类默认保留每组质量最好的资源，并选中其余资源；用户可调整选择，
 * 再通过系统媒体删除确认完成实际清理。
 */
class GroupDetailActivity : Activity() {
    private lateinit var category: ProductCategory
    private lateinit var categoryType: ProductCategoryType
    private lateinit var scanner: SimilarMediaScanner
    private lateinit var database: ScanDatabase
    private lateinit var groupRecycler: RecyclerView
    private lateinit var selectAllButton: TextView
    private lateinit var sortButton: TextView
    private lateinit var deleteButton: Button
    private var groupAdapter: GroupAdapter? = null
    private var gridAdapter: GridAdapter? = null
    private val selectedUris = linkedSetOf<String>()
    private val bestUris = linkedSetOf<String>()
    private val pendingDeleteUris = linkedSetOf<String>()
    private var sortMode = SortMode.NEWEST
    private var selectionInitialized = false
    private var receiverRegistered = false
    private var gridLayoutInitialized = false
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
        scanner = SimilarMediaScanner(this)
        database = ScanDatabase(applicationContext)
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
        sortButton.setOnClickListener { showSortDialog() }
        sortButton.text = sortMode.shortLabel
        deleteButton.setOnClickListener { requestDeleteSelected() }
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
        category = ProductCategoryBuilder.build(scanner.loadCachedGroups())
            .first { it.type == categoryType }
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
            val sortedAssets = sortAssets(category.assets)
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
                        val tempGroup = SimilarGroup(
                            id = 0,
                            title = categoryType.title,
                            subtitle = "",
                            category = com.example.similarscandemo.model.GroupCategory.SIMILAR,
                            kind = com.example.similarscandemo.model.MediaKind.PHOTO,
                            assets = sortedAssets
                        )
                        openPreview(tempGroup, sortedAssets.indexOf(asset))
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

    private fun showSortDialog() {
        val modes = SortMode.entries
        AlertDialog.Builder(this)
            .setTitle("Sort media")
            .setSingleChoiceItems(
                modes.map { it.label }.toTypedArray(),
                sortMode.ordinal
            ) { dialog, which ->
                sortMode = modes[which]
                sortButton.text = sortMode.shortLabel
                dialog.dismiss()
                renderContent()
            }
            .show()
    }

    private fun sortedGroups(): List<SimilarGroup> {
        return category.groups
            .map { it.copy(assets = sortAssets(it.assets, keepBestFirst = true)) }
            .sortedByDescending { group ->
                when (sortMode) {
                    SortMode.RECOMMENDED -> group.assets.maxOfOrNull { it.qualityScore } ?: 0.0
                    SortMode.NEWEST ->
                        (group.assets.maxOfOrNull { mediaTimeKey(it) } ?: 0L).toDouble()
                    SortMode.OLDEST ->
                        -(group.assets.minOfOrNull { mediaTimeKey(it) } ?: 0L).toDouble()
                    SortMode.LARGEST -> group.assets.sumOf { it.size }.toDouble()
                    SortMode.SMALLEST -> -group.assets.sumOf { it.size }.toDouble()
                }
            }
    }

    private fun sortAssets(
        assets: List<MediaAsset>,
        keepBestFirst: Boolean = false
    ): List<MediaAsset> {
        val sorted = when (sortMode) {
            SortMode.RECOMMENDED -> assets.sortedWith(
                compareByDescending<MediaAsset> { it.qualityScore }
                    .thenByDescending { it.isFavorite }
                    .thenByDescending { it.isEdited }
                    .thenByDescending { it.width.toLong() * it.height.toLong() }
            )
            /*
             * 默认展示顺序使用媒体资源时间倒序。createdAt 是当前项目统一后的媒体时间，
             * dateAdded/id 作为兜底，保证同一时间戳下顺序稳定。
             */
            SortMode.NEWEST -> assets.sortedWith(
                compareByDescending<MediaAsset> { it.createdAt.time }
                    .thenByDescending { it.dateAdded }
                    .thenByDescending { it.id }
            )
            SortMode.OLDEST -> assets.sortedWith(
                compareBy<MediaAsset> { it.createdAt.time }
                    .thenBy { it.dateAdded }
                    .thenBy { it.id }
            )
            SortMode.LARGEST -> assets.sortedByDescending { it.size }
            SortMode.SMALLEST -> assets.sortedBy { it.size }
        }
        if (!keepBestFirst || sorted.size < 2) return sorted

        /*
         * 相似/重复组中 Best 是推荐保留项。无论当前展示按时间还是大小排序，
         * Best 都固定放在第一位，剩余资源再按当前排序规则展示。
         */
        val bestUri = assets.firstOrNull { bestUris.contains(it.uri.toString()) }
            ?.uri
            ?.toString()
            ?: return sorted
        return sorted.sortedBy { if (it.uri.toString() == bestUri) 0 else 1 }
    }

    private fun mediaTimeKey(asset: MediaAsset): Long {
        return maxOf(asset.createdAt.time, asset.dateAdded * 1000L)
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
        val allUris = category.assets.map { it.uri.toString() }
        if (selectedUris.size == allUris.size) selectedUris.clear()
        else selectedUris.addAll(allUris)
        renderContent()
    }

    private fun updateSelectionControls() {
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
        val marked = database.markDeletePending(selectedUris)
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
        database.finalizeDelete(pendingDeleteUris)
        DeleteOperationStore.finish(this)
        selectedUris.removeAll(pendingDeleteUris)
        pendingDeleteUris.clear()
        setResult(RESULT_OK)
        reloadLatestCategory()
    }

    private fun cancelDeletion() {
        database.restoreDeletePending(pendingDeleteUris)
        DeleteOperationStore.finish(this)
        pendingDeleteUris.clear()
        reloadLatestCategory()
    }

    private fun openPreview(group: SimilarGroup, position: Int) {
        val asset = group.assets.getOrNull(position) ?: return
        if (asset.kind == com.example.similarscandemo.model.MediaKind.VIDEO || 
            asset.kind == com.example.similarscandemo.model.MediaKind.SCREEN_RECORDING) {
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
            category = com.example.similarscandemo.model.GroupCategory.SIMILAR,
            kind = com.example.similarscandemo.model.MediaKind.PHOTO,
            assets = assets
        )
        openPreview(tempGroup, position)
    }

    companion object {
        const val EXTRA_CATEGORY_TYPE = "extra_category_type"
        private const val DELETE_REQUEST_CODE = 2002
        private const val STATE_PENDING_DELETE_URIS = "pending_delete_uris"
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

    private enum class SortMode(val label: String, val shortLabel: String) {
        RECOMMENDED("Recommended quality", "Best"),
        NEWEST("Newest first", "Newest"),
        OLDEST("Oldest first", "Oldest"),
        LARGEST("Largest first", "Largest"),
        SMALLEST("Smallest first", "Smallest")
    }
}
