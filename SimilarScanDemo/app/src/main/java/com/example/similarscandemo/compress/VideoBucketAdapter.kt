package com.example.similarscandemo.compress

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clean.videocompress.api.model.VideoBucket
import com.example.similarscandemo.R
import com.example.similarscandemo.util.FormatUtils

class VideoBucketAdapter(
    private val context: Context,
    private var buckets: List<VideoBucket>
) : BaseAdapter() {
    fun submitList(newBuckets: List<VideoBucket>) {
        buckets = newBuckets
        notifyDataSetChanged()
    }

    override fun getCount(): Int = buckets.size
    override fun getItem(position: Int): VideoBucket = buckets[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_video_bucket, parent, false)
        val bucket = getItem(position)
        view.setOnClickListener { openBucket(bucket) }
        view.findViewById<TextView>(R.id.bucketTitle).text = bucket.title
        view.findViewById<TextView>(R.id.bucketSubtitle).text = bucket.subtitle
        view.findViewById<TextView>(R.id.bucketStats).text =
            "${bucket.videos.size} Videos · Save ${FormatUtils.formatBytes(bucket.estimatedSavingBytes)}"
        val previewRecycler = view.findViewById<RecyclerView>(R.id.previewRecycler)
        if (previewRecycler.layoutManager == null) {
            previewRecycler.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        }
        /*
         * 使用横向 RecyclerView 懒加载预览 item。这样仍然可以横向查看该分组的全部视频，
         * 但不会在首页一次性 inflate 79 个卡片，进入 Compress 页会快很多。
         */
        previewRecycler.adapter = VideoPreviewAdapter(context, bucket.videos) { openBucket(bucket) }
        return view
    }

    private fun openBucket(bucket: VideoBucket) {
        context.startActivity(
            Intent(context, VideoBucketActivity::class.java)
                .putExtra(VideoBucketActivity.EXTRA_BUCKET_KEY, bucket.key)
        )
    }

}
