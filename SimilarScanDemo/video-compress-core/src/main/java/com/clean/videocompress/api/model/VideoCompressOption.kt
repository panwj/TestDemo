package com.clean.videocompress.api.model

/**
 * 单个压缩档位。
 *
 * compressionRatePercent 表示预计减少的码率比例，例如 50 代表目标码率约为原码率的 50%。
 */
data class VideoCompressOption(
    val key: String,
    val title: String,
    val description: String,
    val compressionRatePercent: Int
) {
    companion object {
        /**
         * SDK 默认的三档压缩配置。
         *
         * 业务层可以通过 VideoCompressConfig 覆盖这些档位名称和压缩比例。
         */
        fun defaults(): List<VideoCompressOption> {
            return listOf(
                VideoCompressOption("low", "Low Quality", "Save the most space", 70),
                VideoCompressOption("medium", "Medium Quality", "Balance size and clarity", 50),
                VideoCompressOption("high", "High Quality", "Keep more detail", 30)
            )
        }
    }
}
