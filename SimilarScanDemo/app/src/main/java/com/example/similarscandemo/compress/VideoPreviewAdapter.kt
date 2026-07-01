package com.example.similarscandemo.compress

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clean.videocompress.api.model.CompressVideoAsset
import com.example.similarscandemo.R
import com.example.similarscandemo.util.FormatUtils

/**
 * Compress 首页分组横向预览列表。
 *
 * 这里使用 RecyclerView 而不是 HorizontalScrollView + LinearLayout，避免大分组一次性创建
 * 全部 item 导致首页明显卡顿。点击任意预览 item 只进入分组详情页。
 */
class VideoPreviewAdapter(
    private val context: Context,
    private val videos: List<CompressVideoAsset>,
    private val onClick: () -> Unit
) : RecyclerView.Adapter<VideoPreviewAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_compress_video, parent, false)
        view.layoutParams = RecyclerView.LayoutParams(dp(184), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            rightMargin = dp(12)
        }
        return Holder(view)
    }

    override fun getItemCount(): Int = videos.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val asset = videos[position]
        holder.name.text = asset.displayName
        holder.size.text = FormatUtils.formatBytes(asset.sizeBytes)
        VideoThumbLoader.load(holder.thumb, asset, 220)
        holder.itemView.setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.videoThumb)
        val name: TextView = view.findViewById(R.id.videoName)
        val size: TextView = view.findViewById(R.id.videoSize)
    }
}
