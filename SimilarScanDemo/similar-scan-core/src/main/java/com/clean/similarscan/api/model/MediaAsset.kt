package com.clean.similarscan.api.model

import android.net.Uri
import java.util.Date

/**
 * SDK 对外媒体资源。
 *
 * 该对象只包含接入方展示、预览、删除所需字段，不暴露指纹、hash、数据库状态等内部信息。
 */
data class MediaAsset(
    val id: Long,
    val uri: Uri,
    val kind: MediaKind,
    val name: String,
    val width: Int,
    val height: Int,
    val duration: Long = 0L,
    val size: Long,
    val createdAt: Date,
    val updatedAt: Date,
    val dateAdded: Long = 0L,
    val bucket: String,
    val pathHint: String,
    val mimeType: String = "",
    val isFavorite: Boolean = false,
    val isEdited: Boolean = false,
    val generationAdded: Long = 0L,
    val generationModified: Long = 0L,
    val chatSource: String? = null,
    val contentSha256: String? = null,
    val qualityScore: Double = 0.0
)
