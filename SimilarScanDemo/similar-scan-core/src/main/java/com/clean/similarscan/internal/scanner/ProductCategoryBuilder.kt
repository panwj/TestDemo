package com.clean.similarscan.internal.scanner

import com.clean.similarscan.internal.model.GroupCategory
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.model.ProductCategory
import com.clean.similarscan.internal.model.ProductCategoryType
import com.clean.similarscan.internal.model.SimilarGroup

/**
 * 将数据库扫描组转换成与产品首页一致的固定分类顺序。
 */
object ProductCategoryBuilder {
    /**
     * 将底层 SimilarGroup 列表转换成产品首页固定分类。
     *
     * 数据库输出的是相似组、重复组和 Other 组；其中普通 Other Photos 与 Chat Photos
     * 已在数据库查询阶段按 chat_source 拆分，这里只负责按固定 ProductCategoryType 顺序返回。
     */
    fun build(groups: List<SimilarGroup>): List<ProductCategory> {
        /*
         * 数据库重建分组时已经保证 Duplicate/Similar 互斥；这里再做一次展示层防御，
         * 避免极端情况下同一资源同时参与两个分类导致首页统计重复累计。
         */
        val duplicateAssetKeys = groups
            .filter { it.category == GroupCategory.DUPLICATE }
            .flatMap { it.assets }
            .mapTo(mutableSetOf()) { it.kind to it.id }
        val normalizedGroups = groups.mapNotNull { group ->
            if (group.category != GroupCategory.SIMILAR) return@mapNotNull group
            val assets = group.assets.filterNot { (it.kind to it.id) in duplicateAssetKeys }
            /*
             * 首页可只加载每组少量预览资源，因此不能用 assets.size 判断真实分组是否有效。
             * totalAssetCount 来自数据库聚合，表示完整分组数量；assets 只代表本次接口返回的展示样本。
             */
            if (group.totalAssetCount < 2) null else group.copy(assets = assets)
        }.map(::sortGroupByMediaTimeDesc)

        val otherPhotoGroups = normalizedGroups
            .filter { it.category == GroupCategory.OTHER && it.kind == MediaKind.PHOTO }
        val chatPhotoGroups = otherPhotoGroups.filter { it.title == RAW_CHAT_PHOTOS_TITLE }
        val regularOtherPhotoGroups = otherPhotoGroups.filter { it.title != RAW_CHAT_PHOTOS_TITLE }

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
                    synthetic(type.title, MediaKind.PHOTO, chatPhotoGroups)
                ProductCategoryType.SIMILAR_SCREEN_RECORDINGS ->
                    matched(normalizedGroups, GroupCategory.SIMILAR, MediaKind.SCREEN_RECORDING)
                ProductCategoryType.OTHER_SCREEN_RECORDINGS ->
                    matched(normalizedGroups, GroupCategory.OTHER, MediaKind.SCREEN_RECORDING)
                ProductCategoryType.OTHER_VIDEOS ->
                    matched(normalizedGroups, GroupCategory.OTHER, MediaKind.VIDEO)
                ProductCategoryType.OTHER ->
                    synthetic(type.title, MediaKind.PHOTO, regularOtherPhotoGroups)
            }
            ProductCategory(type, categoryGroups)
        }
    }

    /**
     * 取出指定类型的真实扫描分组，并按组内最新媒体时间倒序展示。
     */
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

    /**
     * 把一组散落资产合成一个展示用分组。
     *
     * Chat Photos、Other 这类产品分类不是数据库里的相似组，而是由一个或多个 Other 资源集合
     * 合成的展示组。
     */
    private fun synthetic(
        title: String,
        kind: MediaKind,
        groups: List<SimilarGroup>
    ): List<SimilarGroup> {
        if (groups.isEmpty()) return emptyList()
        val assets = groups.flatMap { it.assets }.distinctBy { it.kind to it.id }
        val sortedAssets = assets.sortedWith(MEDIA_TIME_DESC)
        return listOf(
            SimilarGroup(
                title = title,
                subtitle = "",
                category = GroupCategory.OTHER,
                kind = kind,
                assets = sortedAssets,
                totalAssetCount = groups.sumOf { it.totalAssetCount },
                totalSizeBytes = groups.sumOf { it.totalSizeBytes }
            )
        )
    }

    /**
     * 规范组内排序。
     *
     * Similar/Duplicate 组要求 Best 永远排第一，其余资源按媒体时间倒序；Other 类分组不做
     * Best 提前，只保留时间倒序。
     */
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

    /**
     * 选择推荐保留资源。
     *
     * 质量分最高优先，其次考虑收藏、编辑状态、分辨率、文件大小和拍摄时间。
     */
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

    private const val RAW_CHAT_PHOTOS_TITLE = "Chat Photos"
}
