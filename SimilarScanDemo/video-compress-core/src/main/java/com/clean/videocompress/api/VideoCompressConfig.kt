package com.clean.videocompress.api

import com.clean.videocompress.api.model.VideoCompressOption

/**
 * 视频压缩 SDK 配置。
 *
 * 三档压缩是可配置项，Demo 可按产品实验调整名称、压缩比例和展示顺序。
 */
data class VideoCompressConfig(
    /** 压缩引擎类型，默认使用 Media3 主链路。 */
    val engineType: VideoCompressEngineType = VideoCompressEngineType.MEDIA3_TRANSFORMER,
    /** 可展示给用户选择的压缩档位。 */
    val options: List<VideoCompressOption> = VideoCompressOption.defaults(),
    /** 压缩首页视频分桶规则。 */
    val bucketRules: List<VideoCompressBucketRule> = VideoCompressBucketRule.defaults()
)

/**
 * 压缩引擎类型。
 */
enum class VideoCompressEngineType {
    /** 默认生产方案。 */
    MEDIA3_TRANSFORMER,
    /** 原生备用方案，仅建议作为兜底或实验开关。 */
    NATIVE_CODEC
}

/**
 * 视频分桶规则。
 */
data class VideoCompressBucketRule(
    val key: String,
    val title: String,
    val subtitle: String,
    val minEstimatedSavingBytes: Long,
    val color: Int
) {
    companion object {
        /**
         * 默认分桶规则。
         */
        fun defaults(): List<VideoCompressBucketRule> {
            return listOf(
                VideoCompressBucketRule(
                    key = "extreme",
                    title = "Extreme Space",
                    subtitle = "Largest files with the highest saving potential",
                    minEstimatedSavingBytes = 150L * 1024L * 1024L,
                    color = 0xFFE85454.toInt()
                ),
                VideoCompressBucketRule(
                    key = "moderate",
                    title = "Moderate Space",
                    subtitle = "Balanced compression candidates",
                    minEstimatedSavingBytes = 40L * 1024L * 1024L,
                    color = 0xFFF59E0B.toInt()
                ),
                VideoCompressBucketRule(
                    key = "light",
                    title = "Light Space",
                    subtitle = "Small videos that can still be optimized",
                    minEstimatedSavingBytes = 0L,
                    color = 0xFF38BDF8.toInt()
                )
            )
        }
    }
}
