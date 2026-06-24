package com.example.similarscandemo.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import com.example.similarscandemo.R
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.scanner.MediaBitmapLoader

/**
 * 首页分类模块的三张大缩略图预览。
 */
class CategoryPreviewAdapter(
    activity: Activity,
    private val assets: List<MediaAsset>
) : BaseAdapter() {
    private val loader = MediaBitmapLoader(activity.contentResolver)

    override fun getCount(): Int = assets.size
    override fun getItem(position: Int): MediaAsset = assets[position]
    override fun getItemId(position: Int): Long = assets[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_preview, parent, false)
        val asset = getItem(position)
        val image = view.findViewById<ImageView>(R.id.categoryPreviewImage)
        image.setImageResource(android.R.color.transparent)
        loader.loadBitmap(asset, 360)?.let(image::setImageBitmap)
        return view
    }
}
