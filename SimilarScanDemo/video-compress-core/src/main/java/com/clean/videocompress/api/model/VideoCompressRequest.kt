package com.clean.videocompress.api.model

data class VideoCompressRequest(
    val asset: CompressVideoAsset,
    val option: VideoCompressOption
)
