package com.clean.videocompress.api.model

/**
 * 压缩首页使用的视频分桶。
 *
 * SDK 只根据体积和预计节省空间给出分类结果，具体 UI 样式、点击行为和免费次数
 * 仍由业务层决定。
 */
data class VideoBucket(
    /** 分桶唯一标识，例如 extreme / moderate / light。 */
    val key: String,
    /** 分桶展示标题。 */
    val title: String,
    /** 分桶副标题，用于解释该分类的压缩价值。 */
    val subtitle: String,
    /** 业务层可直接使用的分类颜色。 */
    val color: Int,
    /** 当前分桶下的视频列表。 */
    val videos: List<CompressVideoAsset>,
    /** 当前分桶下原视频总大小。 */
    val totalBytes: Long,
    /** 当前分桶预计最多可节省的空间。 */
    val estimatedSavingBytes: Long
)
