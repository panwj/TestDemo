package com.clean.similarscan.api.model

import android.net.Uri
import java.util.Date

/**
 * SDK 对外媒体资源。
 *
 * 该对象只包含接入方展示、预览、删除所需字段，不暴露指纹、hash、数据库状态等内部信息。
 */
data class MediaAsset(
    /** SDK 内部数据库主键，用于分页、分组和去重；不是 MediaStore._ID。 */
    val id: Long,
    /** 可用于预览、播放或删除授权请求的系统媒体 Uri。 */
    val uri: Uri,
    /** SDK 识别出的媒体类型，来自 MediaStore 基础类型和文件名/路径规则补充分流。 */
    val kind: MediaKind,
    /** 媒体显示名称，通常来自 MediaStore.DISPLAY_NAME。 */
    val name: String,
    /** 媒体宽度，图片/视频解码或系统库不可用时可能为 0。 */
    val width: Int,
    /** 媒体高度，图片/视频解码或系统库不可用时可能为 0。 */
    val height: Int,
    /** 视频/录屏时长，单位毫秒；图片和截图固定为 0。 */
    val duration: Long = 0L,
    /** 文件大小，单位字节。 */
    val size: Long,
    /** 拍摄或创建时间，优先使用 MediaStore 的日期字段。 */
    val createdAt: Date,
    /** 最近更新时间，主要用于判断资源是否需要重新计算指纹。 */
    val updatedAt: Date,
    /** MediaStore.DATE_ADDED，单位秒，用于与系统相册时间倒序展示保持一致。 */
    val dateAdded: Long = 0L,
    /** MediaStore.BUCKET_DISPLAY_NAME，可用于业务层按相册展示。 */
    val bucket: String,
    /** 文件路径提示，仅用于分类辅助和诊断，业务层不应依赖它执行文件操作。 */
    val pathHint: String,
    /** MIME 类型，用于业务层展示或播放前判断格式。 */
    val mimeType: String = "",
    /** 系统收藏标记；旧系统或设备不支持时为 false。 */
    val isFavorite: Boolean = false,
    /** 系统编辑标记；旧系统或设备不支持时为 false。 */
    val isEdited: Boolean = false,
    /** MediaStore generation_added，用于增量扫描判断；不支持时为 0。 */
    val generationAdded: Long = 0L,
    /** MediaStore generation_modified，用于增量扫描判断；不支持时为 0。 */
    val generationModified: Long = 0L,
    /** 聊天来源识别结果，当前产品分类默认不单独暴露聊天图片分类。 */
    val chatSource: String? = null,
    /** 文件内容 SHA-256；默认可能为空，取决于扫描请求是否启用或后续是否补算。 */
    val contentSha256: String? = null,
    /** 保留建议排序使用的质量分，分值越高越适合作为同组 Best。 */
    val qualityScore: Double = 0.0
)
