package com.example.similarscandemo.scanner

import com.example.similarscandemo.model.GroupCategory
import com.example.similarscandemo.model.MediaAsset
import com.example.similarscandemo.model.MediaKind
import com.example.similarscandemo.model.ProductCategory
import com.example.similarscandemo.model.ProductCategoryType
import com.example.similarscandemo.model.SimilarGroup

/**
 * 将数据库扫描组转换成与竞品首页一致的固定分类顺序。
 */
object ProductCategoryBuilder {
    fun build(groups: List<SimilarGroup>): List<ProductCategory> {
        /*
         * 数据库已经保证 Duplicate/Similar 互斥；这里再做一次展示层防御，
         * 让旧版本数据库升级后即使尚未全量重扫，分类总数也不会重复累计。
         */
        val duplicateAssetKeys = groups
            .filter { it.category == GroupCategory.DUPLICATE }
            .flatMap { it.assets }
            .mapTo(mutableSetOf()) { it.kind to it.id }
        val normalizedGroups = groups.mapNotNull { group ->
            if (group.category != GroupCategory.SIMILAR) return@mapNotNull group
            val assets = group.assets.filterNot { (it.kind to it.id) in duplicateAssetKeys }
            if (assets.size < 2) null else group.copy(assets = assets)
        }

        val otherPhotos = normalizedGroups
            .filter { it.category == GroupCategory.OTHER && it.kind == MediaKind.PHOTO }
            .flatMap { it.assets }
        val chatPhotos = otherPhotos.filter(MediaClassifier::looksLikeChatPhoto)
        val regularOtherPhotos = otherPhotos.filterNot(MediaClassifier::looksLikeChatPhoto)

        return ProductCategoryType.entries.map { type ->
            val categoryGroups = when (type) {
                ProductCategoryType.SIMILAR -> matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.PHOTO)
                ProductCategoryType.DUPLICATES -> normalizedGroups.filter {
                    it.category == GroupCategory.DUPLICATE &&
                        (it.kind == MediaKind.PHOTO || it.kind == MediaKind.SCREENSHOT)
                }
                ProductCategoryType.SIMILAR_SCREENSHOTS ->
                    matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.SCREENSHOT)
                ProductCategoryType.SIMILAR_VIDEOS ->
                    matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.VIDEO)
                ProductCategoryType.OTHER_SCREENSHOTS ->
                    matched(normalizedGroups, GroupCategory.OTHER, MediaKind.SCREENSHOT)
                ProductCategoryType.CHAT_PHOTOS ->
                    synthetic(type.title, MediaKind.PHOTO, chatPhotos)
                ProductCategoryType.SIMILAR_SCREEN_RECORDINGS ->
                    matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.SCREEN_RECORDING)
                ProductCategoryType.OTHER_SCREEN_RECORDINGS ->
                    matched(normalizedGroups, GroupCategory.OTHER, MediaKind.SCREEN_RECORDING)
                ProductCategoryType.OTHER_VIDEOS ->
                    matched(normalizedGroups, GroupCategory.OTHER, MediaKind.VIDEO)
                ProductCategoryType.OTHER ->
                    synthetic(type.title, MediaKind.PHOTO, regularOtherPhotos)
            }
            ProductCategory(type, categoryGroups)
        }
    }

    private fun matched(
        groups: List<SimilarGroup>,
        category: GroupCategory,
        kind: MediaKind
    ): List<SimilarGroup> {
        return groups.filter { it.category == category && it.kind == kind }
    }

    private fun synthetic(
        title: String,
        kind: MediaKind,
        assets: List<MediaAsset>
    ): List<SimilarGroup> {
        if (assets.isEmpty()) return emptyList()
        return listOf(
            SimilarGroup(
                title = title,
                subtitle = "",
                category = GroupCategory.OTHER,
                kind = kind,
                assets = assets
            )
        )
    }
}
