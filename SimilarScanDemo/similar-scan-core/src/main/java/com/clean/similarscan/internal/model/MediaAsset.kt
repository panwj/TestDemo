package com.clean.similarscan.internal.model

import android.net.Uri
import com.clean.similarscan.internal.similarity.CombinedHash
import java.util.Date

/**
 * MediaStore 中的一条本地资源。
 *
 * hash 是懒加载字段：只有进入相似比较时才解码缩略图并计算，避免扫描列表阶段就消耗大量内存。
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
    var contentSha256: String? = null,
    var qualityScore: Double = 0.0,
    var hash: CombinedHash? = null
)
