package com.clean.videocompress.api.model

import android.net.Uri

/**
 * 压缩成功结果。
 */
data class VideoCompressResult(
    /** 原始视频资源。 */
    val sourceAsset: CompressVideoAsset,
    /** 压缩后视频在系统媒体库中的 Uri。 */
    val outputUri: Uri,
    /** 压缩后文件大小，单位 byte。 */
    val outputSizeBytes: Long,
    /** 节省空间，单位 byte。 */
    val savedBytes: Long,
    /** 从开始压缩到保存完成的总耗时。 */
    val elapsedMs: Long
)
