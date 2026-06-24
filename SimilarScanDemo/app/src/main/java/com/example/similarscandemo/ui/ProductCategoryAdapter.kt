package com.example.similarscandemo.ui

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import com.example.similarscandemo.GroupDetailActivity
import com.example.similarscandemo.R
import com.example.similarscandemo.model.MediaKind
import com.example.similarscandemo.model.ProductCategory
import com.example.similarscandemo.util.FormatUtils

/**
 * 与竞品首页一致的纵向分类列表适配器。
 */
class ProductCategoryAdapter(
    private val activity: Activity,
    private var categories: List<ProductCategory>
) : BaseAdapter() {
    fun submitList(newCategories: List<ProductCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    override fun getCount(): Int = categories.size
    override fun getItem(position: Int): ProductCategory = categories[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(activity)
            .inflate(R.layout.item_product_category, parent, false)
        val category = getItem(position)
        val assets = category.assets
        val unit = when {
            assets.isEmpty() -> "Photos"
            assets.first().kind == MediaKind.VIDEO ||
                assets.first().kind == MediaKind.SCREEN_RECORDING -> "Videos"
            else -> "Photos"
        }

        view.findViewById<TextView>(R.id.categoryTitle).text = category.type.title
        view.findViewById<TextView>(R.id.categoryStats).text =
            "${category.itemCount} $unit · ${FormatUtils.formatBytes(category.totalSize)}"

        val previewGrid = view.findViewById<GridView>(R.id.categoryPreviewGrid)
        previewGrid.visibility = if (assets.isEmpty()) View.GONE else View.VISIBLE
        previewGrid.isEnabled = false
        previewGrid.isClickable = false
        previewGrid.adapter = CategoryPreviewAdapter(activity, assets.take(3))

        view.setOnClickListener {
            activity.startActivity(
                Intent(activity, GroupDetailActivity::class.java)
                    .putExtra(GroupDetailActivity.EXTRA_CATEGORY_TYPE, category.type.name)
            )
        }
        return view
    }
}
