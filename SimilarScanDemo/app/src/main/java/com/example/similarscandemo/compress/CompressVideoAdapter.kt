package com.example.similarscandemo.compress

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.clean.videocompress.api.model.CompressVideoAsset
import com.example.similarscandemo.R
import com.example.similarscandemo.util.FormatUtils

class CompressVideoAdapter(
    private val context: Context,
    private var videos: List<CompressVideoAsset>
) : BaseAdapter() {
    fun submitList(newVideos: List<CompressVideoAsset>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    override fun getCount(): Int = videos.size
    override fun getItem(position: Int): CompressVideoAsset = videos[position]
    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_compress_video, parent, false)
        val asset = getItem(position)
        view.findViewById<TextView>(R.id.videoName).text = asset.displayName
        view.findViewById<TextView>(R.id.videoSize).text = FormatUtils.formatBytes(asset.sizeBytes)
        VideoThumbLoader.load(view.findViewById<ImageView>(R.id.videoThumb), asset, 260)
        return view
    }
}
