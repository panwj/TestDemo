package com.clean.videocompress.api

import com.clean.videocompress.api.model.VideoCompressOption

/**
 * 视频压缩 SDK 配置。
 *
 * 三档压缩是可配置项，Demo 可按产品实验调整名称、压缩比例和展示顺序。
 */
data class VideoCompressConfig(
    val engineType: VideoCompressEngineType = VideoCompressEngineType.MEDIA3_TRANSFORMER,
    val options: List<VideoCompressOption> = VideoCompressOption.defaults(),
    val bucketRules: List<VideoCompressBucketRule> = VideoCompressBucketRule.defaults()
)

enum class VideoCompressEngineType {
    MEDIA3_TRANSFORMER,
    NATIVE_CODEC
}

data class VideoCompressBucketRule(
    val key: String,
    val title: String,
    val subtitle: String,
    val minEstimatedSavingBytes: Long,
    val color: Int
) {
    companion object {
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
