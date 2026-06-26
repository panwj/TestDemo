package com.clean.similarscan.internal.scanner

import com.clean.similarscan.internal.model.GroupCategory
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ProductCategory
import com.clean.similarscan.internal.model.ProductCategoryType
import com.clean.similarscan.internal.model.SimilarGroup

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
        }.map(::sortGroupByMediaTimeDesc)

        val otherPhotos = normalizedGroups
            .filter { it.category == GroupCategory.OTHER && it.kind == MediaKind.PHOTO }
            .flatMap { it.assets }
        val chatPhotos = otherPhotos.filter(MediaClassifier::looksLikeChatPhoto)
        val regularOtherPhotos = otherPhotos.filterNot(MediaClassifier::looksLikeChatPhoto)

        return ProductCategoryType.entries.map { type ->
            val categoryGroups = when (type) {
                ProductCategoryType.SIMILAR -> matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.PHOTO)
                ProductCategoryType.DUPLICATES -> normalizedGroups
                    .filter {
                        it.category == GroupCategory.DUPLICATE &&
                            (it.kind == MediaKind.PHOTO || it.kind == MediaKind.SCREENSHOT)
                    }
                    .map(::sortGroupByMediaTimeDesc)
                    .sortedWith(
                        compareByDescending<SimilarGroup> { group ->
                            group.assets.maxOfOrNull { it.createdAt.time } ?: 0L
                        }.thenByDescending { it.id }
                    )
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
        /*
         * 首页和详情页默认按照“组内最新媒体时间”倒序展示扫描结果。
         * 这样刚拍摄/刚录制的资源会排在前面，也能与系统相册的浏览顺序保持一致。
         */
        return groups
            .filter { it.category == category && it.kind == kind }
            .map(::sortGroupByMediaTimeDesc)
            .sortedWith(
                compareByDescending<SimilarGroup> { group ->
                    group.assets.maxOfOrNull { it.createdAt.time } ?: 0L
                }.thenByDescending { it.id }
            )
    }

    private fun synthetic(
        title: String,
        kind: MediaKind,
        assets: List<MediaAsset>
    ): List<SimilarGroup> {
        if (assets.isEmpty()) return emptyList()
        val sortedAssets = assets.sortedWith(MEDIA_TIME_DESC)
        return listOf(
            SimilarGroup(
                title = title,
                subtitle = "",
                category = GroupCategory.OTHER,
                kind = kind,
                assets = sortedAssets,
                totalAssetCount = sortedAssets.size,
                totalSizeBytes = sortedAssets.sumOf { it.size }
            )
        )
    }

    private fun sortGroupByMediaTimeDesc(group: SimilarGroup): SimilarGroup {
        val timeSorted = group.assets.sortedWith(MEDIA_TIME_DESC)
        val sortedAssets = if (
            group.category == GroupCategory.SIMILAR ||
            group.category == GroupCategory.DUPLICATE
        ) {
            val best = bestAsset(timeSorted)
            if (best == null) {
                timeSorted
            } else {
                listOf(best) + timeSorted.filterNot { it.uri == best.uri }
            }
        } else {
            timeSorted
        }
        return group.copy(assets = sortedAssets)
    }

    private fun bestAsset(assets: List<MediaAsset>): MediaAsset? {
        return assets.maxWithOrNull(
            compareBy<MediaAsset>(
                { it.qualityScore },
                { it.isFavorite },
                { it.isEdited },
                { it.width.toLong() * it.height.toLong() },
                { it.size },
                { it.createdAt.time }
            )
        )
    }

    private val MEDIA_TIME_DESC = compareByDescending<MediaAsset> { it.createdAt.time }
        .thenByDescending { it.dateAdded }
        .thenByDescending { it.id }
}
