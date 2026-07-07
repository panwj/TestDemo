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
import com.clean.similarscan.api.model.MediaKind
import com.clean.similarscan.api.model.ProductCategory
import com.example.similarscandemo.util.FormatUtils

/**
 * 与竞品首页一致的纵向分类列表适配器。
 */
class ProductCategoryAdapter(
    private val activity: Activity,
    private var categories: List<ProductCategory>,
    private val previewAssetLimit: Int
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
        /*
         * 首页预览要保留分组语义：
         * - Similar/Duplicates/Similar Videos 这类 grouped 分类，预览应来自同一个首组；
         *   如果直接把整个分类 assets 打平再按时间排序，Duplicates 可能显示两个不同组的图，
         *   看起来就像第二张缩略图加载错了。
         * - Other 这类平铺分类没有组语义，继续按媒体时间倒序取预览资源。
         */
        val previewAssets = if (category.type.grouped) {
            MediaDisplaySorter.newestGroupFirst(category.groups)
                .firstOrNull()
                ?.assets
                .orEmpty()
                .take(previewAssetLimit)
        } else {
            MediaDisplaySorter.newestFirst(category.assets).take(previewAssetLimit)
        }
        val unit = when {
            previewAssets.isEmpty() -> "Photos"
            previewAssets.first().kind == MediaKind.VIDEO ||
                previewAssets.first().kind == MediaKind.SCREEN_RECORDING -> "Videos"
            else -> "Photos"
        }

        view.findViewById<TextView>(R.id.categoryTitle).text = category.type.title
        view.findViewById<TextView>(R.id.categoryStats).text =
            "${category.itemCount} $unit · ${FormatUtils.formatBytes(category.totalSize)}"

        val previewGrid = view.findViewById<GridView>(R.id.categoryPreviewGrid)
        previewGrid.visibility = if (previewAssets.isEmpty()) View.GONE else View.VISIBLE
        previewGrid.isEnabled = false
        previewGrid.isClickable = false
        previewGrid.adapter = CategoryPreviewAdapter(activity, previewAssets)

        view.setOnClickListener {
            activity.startActivity(
                Intent(activity, GroupDetailActivity::class.java)
                    .putExtra(GroupDetailActivity.EXTRA_CATEGORY_TYPE, category.type.name)
            )
        }
        return view
    }

}
