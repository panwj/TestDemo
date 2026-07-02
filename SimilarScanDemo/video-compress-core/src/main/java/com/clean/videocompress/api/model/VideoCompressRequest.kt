package com.clean.videocompress.api.model

/**
 * 单次压缩请求。
 */
data class VideoCompressRequest(
    /** 待压缩的视频资源。 */
    val asset: CompressVideoAsset,
    /** 用户或业务层选择的压缩档位。 */
    val option: VideoCompressOption
)
