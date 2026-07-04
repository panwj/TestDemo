package com.example.similarscandemo.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.similarscandemo.R
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.SimilarGroup
import com.example.similarscandemo.util.FormatUtils

class GroupAdapter(
    private val activity: Activity,
    private val selectedUris: MutableSet<String>,
    private val bestUris: MutableSet<String>,
    private val onAssetClick: (SimilarGroup, Int) -> Unit,
    private val onAssetSelect: (SimilarGroup, Int) -> Unit,
    private val onGroupLoadMore: (SimilarGroup) -> Unit
) : ListAdapter<SimilarGroup, GroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private val thumbAdapters = mutableListOf<ThumbAdapter>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(activity).inflate(R.layout.item_detail_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateSelection() {
        thumbAdapters.forEach { it.notifySelectionChanged() }
    }

    fun addThumbAdapter(adapter: ThumbAdapter) {
        if (!thumbAdapters.contains(adapter)) {
            thumbAdapters.add(adapter)
        }
    }

    fun removeThumbAdapter(adapter: ThumbAdapter) {
        thumbAdapters.remove(adapter)
    }

    fun clearThumbAdapters() {
        thumbAdapters.clear()
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.detailGroupTitle)
        private val size: TextView = itemView.findViewById(R.id.detailGroupSize)
        private val horizontalRecycler: RecyclerView = itemView.findViewById(R.id.detailGroupHorizontalRecycler)
        private val horizontalLayoutManager = LinearLayoutManager(
            activity,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        private var thumbAdapter: ThumbAdapter? = null
        private var currentGroup: SimilarGroup? = null

        init {
            horizontalRecycler.layoutManager = horizontalLayoutManager
            horizontalRecycler.setHasFixedSize(true)
            horizontalRecycler.itemAnimator = null
            horizontalRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dx <= 0) return
                    val group = currentGroup ?: return
                    if (group.assets.size >= group.totalAssetCount) return
                    val lastVisible = horizontalLayoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= group.assets.size - GROUP_LOAD_MORE_THRESHOLD) {
                        onGroupLoadMore(group)
                    }
                }
            })
        }

        fun bind(group: SimilarGroup) {
            currentGroup = group

            val label = if (group.title.contains("Duplicate", ignoreCase = true)) {
                "${group.totalAssetCount} Duplicates"
            } else {
                "${group.totalAssetCount} Similar"
            }
            title.text = label
            size.text = FormatUtils.formatBytes(group.totalSizeBytes)

            val bestAssetUri = group.assets.firstOrNull { bestUris.contains(it.uri.toString()) }?.uri?.toString()

            if (thumbAdapter == null) {
                thumbAdapter = ThumbAdapter(
                    activity = activity,
                    selectedUris = selectedUris,
                    bestUri = bestAssetUri ?: "",
                    onItemClick = { position -> currentGroup?.let { onAssetClick(it, position) } },
                    onSelectionToggle = { position -> currentGroup?.let { onAssetSelect(it, position) } }
                )
                horizontalRecycler.adapter = thumbAdapter
                addThumbAdapter(thumbAdapter!!)
            } else {
                thumbAdapter?.updateBestUri(bestAssetUri ?: "")
            }

            thumbAdapter?.submitList(group.assets)
        }
    }

    private class GroupDiffCallback : DiffUtil.ItemCallback<SimilarGroup>() {
        override fun areItemsTheSame(oldItem: SimilarGroup, newItem: SimilarGroup): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SimilarGroup, newItem: SimilarGroup): Boolean {
            return oldItem.id == newItem.id &&
                oldItem.assets.size == newItem.assets.size &&
                oldItem.totalAssetCount == newItem.totalAssetCount
        }
    }

    private companion object {
        const val GROUP_LOAD_MORE_THRESHOLD = 4
    }
}

class ThumbAdapter(
    private val activity: Activity,
    private val selectedUris: MutableSet<String>,
    private var bestUri: String,
    private val onItemClick: (Int) -> Unit,
    private val onSelectionToggle: (Int) -> Unit
) : ListAdapter<MediaAsset, ThumbAdapter.ThumbViewHolder>(AssetDiffCallback()) {

    fun updateBestUri(newBestUri: String) {
        bestUri = newBestUri
    }

    fun notifySelectionChanged() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
        val view = LayoutInflater.from(activity).inflate(R.layout.item_thumb, parent, false)
        return ThumbViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ThumbViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.gridImage)
        private val badge: TextView = itemView.findViewById(R.id.gridBadge)
        private val bestBadge: TextView = itemView.findViewById(R.id.bestBadge)
        private val selectionIndicator: TextView = itemView.findViewById(R.id.selectionIndicator)

        fun bind(asset: MediaAsset, position: Int) {
            val badgeText = when (asset.kind) {
                com.clean.similarscan.api.model.MediaKind.VIDEO, 
                com.clean.similarscan.api.model.MediaKind.SCREEN_RECORDING -> "VIDEO"
                else -> ""
            }
            badge.text = badgeText
            badge.visibility = if (badgeText.isEmpty()) View.GONE else View.VISIBLE
            bestBadge.visibility = if (asset.uri.toString() == bestUri) View.VISIBLE else View.GONE

            val isSelected = selectedUris.contains(asset.uri.toString())
            selectionIndicator.text = if (isSelected) "✓" else ""
            selectionIndicator.setBackgroundResource(
                if (isSelected) R.drawable.selection_checked else R.drawable.selection_unchecked
            )

            itemView.setOnClickListener { onItemClick(position) }
            selectionIndicator.setOnClickListener { onSelectionToggle(position) }

            image.setImageResource(android.R.color.transparent)
            ThumbLoader.loadBitmap(image, asset, 300)
        }
    }

    private class AssetDiffCallback : DiffUtil.ItemCallback<MediaAsset>() {
        override fun areItemsTheSame(oldItem: MediaAsset, newItem: MediaAsset): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: MediaAsset, newItem: MediaAsset): Boolean {
            return oldItem.id == newItem.id
        }
    }
}
