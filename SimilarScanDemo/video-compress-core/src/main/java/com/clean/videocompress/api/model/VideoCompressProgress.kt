package com.clean.videocompress.api.model

/**
 * 单个压缩任务的进度。
 *
 * percent 统一为 0~100，elapsedMs 方便业务层展示已耗时或埋点统计。
 */
data class VideoCompressProgress(
    /** 当前正在压缩的视频 id。 */
    val assetId: Long,
    /** 当前任务阶段。 */
    val stage: VideoCompressStage,
    /** 当前阶段折算后的整体进度百分比。 */
    val percent: Int,
    /** 从任务开始到当前回调的耗时，单位毫秒。 */
    val elapsedMs: Long
)

/**
 * 压缩任务阶段。
 */
enum class VideoCompressStage {
    /** 创建任务、检查参数、准备编码器。 */
    PREPARING,
    /** 正在执行转码。 */
    TRANSCODING,
    /** 转码完成，正在写入系统媒体库。 */
    SAVING_TO_MEDIASTORE,
    /** 任务完成。 */
    COMPLETED
}
