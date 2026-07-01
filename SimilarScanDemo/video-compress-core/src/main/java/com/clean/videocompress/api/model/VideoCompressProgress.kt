package com.clean.videocompress.api.model

data class VideoCompressProgress(
    val assetId: Long,
    val stage: VideoCompressStage,
    val percent: Int,
    val elapsedMs: Long
)

enum class VideoCompressStage {
    PREPARING,
    TRANSCODING,
    SAVING_TO_MEDIASTORE,
    COMPLETED
}
