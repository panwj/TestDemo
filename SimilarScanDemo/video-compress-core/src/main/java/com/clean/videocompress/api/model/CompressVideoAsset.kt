package com.clean.videocompress.api.model

import android.net.Uri

/**
 * SDK 对外暴露的视频资源模型。
 *
 * 数据主要来自系统 MediaStore。列表阶段为了保证加载速度，bitrate 可能为 0；
 * 真正开始压缩当前视频时，Media3 主链路会再次读取真实 metadata 参与压缩策略。
 */
data class CompressVideoAsset(
    /** MediaStore 中的视频 id。 */
    val id: Long,
    /** 可用于读取原视频内容的 content Uri。 */
    val uri: Uri,
    /** 系统媒体库中的展示文件名。 */
    val displayName: String,
    /** 原视频大小，单位 byte。 */
    val sizeBytes: Long,
    /** 原视频时长，单位毫秒。 */
    val durationMs: Long,
    /** MediaStore 记录的视频宽度，部分设备可能为 0。 */
    val width: Int,
    /** MediaStore 记录的视频高度，部分设备可能为 0。 */
    val height: Int,
    /** 添加到媒体库的时间，单位秒。 */
    val dateAddedSeconds: Long,
    /** 媒体库记录的修改时间，单位秒。 */
    val dateModifiedSeconds: Long,
    /** 原视频码率。列表阶段通常为 0，压缩时会优先读取真实码率。 */
    val bitrate: Int
)
