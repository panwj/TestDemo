package com.example.similarscandemo.ui

import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.similarscandemo.R
import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.MediaKind

class GridAdapter(
    private val activity: Activity,
    private val selectedUris: MutableSet<String>,
    private val onItemClick: (MediaAsset) -> Unit,
    private val onSelectionToggle: (MediaAsset, Int) -> Unit
) : ListAdapter<MediaAsset, GridAdapter.GridViewHolder>(AssetDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val view = LayoutInflater.from(activity).inflate(R.layout.item_grid, parent, false)
        return GridViewHolder(view)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun notifySelectionChanged(position: Int) {
        notifyItemChanged(position)
    }

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.gridImage)
        private val badge: TextView = itemView.findViewById(R.id.gridBadge)
        private val bestBadge: TextView = itemView.findViewById(R.id.bestBadge)
        private val selectionIndicator: TextView = itemView.findViewById(R.id.selectionIndicator)
        private var currentAsset: MediaAsset? = null

        init {
            itemView.setOnClickListener {
                currentAsset?.let { onItemClick(it) }
            }
            selectionIndicator.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    currentAsset?.let { onSelectionToggle(it, position) }
                }
            }
        }

        fun bind(asset: MediaAsset) {
            currentAsset = asset

            val badgeText = when (asset.kind) {
                MediaKind.VIDEO, MediaKind.SCREEN_RECORDING -> "VIDEO"
                else -> ""
            }
            badge.text = badgeText
            badge.visibility = if (badgeText.isEmpty()) View.GONE else View.VISIBLE
            bestBadge.visibility = View.GONE

            val isSelected = selectedUris.contains(asset.uri.toString())
            selectionIndicator.text = if (isSelected) "✓" else ""
            selectionIndicator.setBackgroundResource(
                if (isSelected) R.drawable.selection_checked else R.drawable.selection_unchecked
            )

            image.setImageResource(android.R.color.transparent)
            ThumbLoader.loadBitmap(image, asset, 300)
        }
    }

    class GridSpacingItemDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            val spanCount = 2

            outRect.top = spacing
            outRect.bottom = spacing

            if (position % spanCount == 0) {
                outRect.left = spacing
                outRect.right = spacing / 2
            } else {
                outRect.left = spacing / 2
                outRect.right = spacing
            }
        }
    }

    private class AssetDiffCallback : DiffUtil.ItemCallback<MediaAsset>() {
        override fun areItemsTheSame(oldItem: MediaAsset, newItem: MediaAsset): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: MediaAsset, newItem: MediaAsset): Boolean {
            return oldItem.id == newItem.id &&
                   oldItem.width == newItem.width &&
                   oldItem.height == newItem.height
        }
    }
}
