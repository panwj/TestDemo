package com.example.similarscandemo.ui

import com.clean.similarscan.api.model.MediaAsset
import com.clean.similarscan.api.model.SimilarGroup

/**
 * Demo 展示层统一排序工具。
 *
 * 注意：这里仅影响业务 UI 展示顺序，不参与 SDK 扫描、指纹计算、相似识别和分组生成。
 * 所有分类在首页预览、详情页网格、分组横滑列表和大图预览中都按媒体时间倒序展示。
 */
object MediaDisplaySorter {
    fun newestFirst(assets: List<MediaAsset>): List<MediaAsset> {
        return assets.sortedWith(
            compareByDescending<MediaAsset> { mediaTimeKey(it) }
                .thenByDescending { it.dateAdded }
                .thenByDescending { it.id }
        )
    }

    fun newestGroupFirst(groups: List<SimilarGroup>): List<SimilarGroup> {
        return groups
            .map { group -> group.copy(assets = newestFirst(group.assets)) }
            .sortedWith(
                compareByDescending<SimilarGroup> { group ->
                    group.assets.maxOfOrNull(::mediaTimeKey) ?: 0L
                }.thenByDescending { it.id }
            )
    }

    private fun mediaTimeKey(asset: MediaAsset): Long {
        return maxOf(asset.createdAt.time, asset.dateAdded * 1000L)
    }
}
