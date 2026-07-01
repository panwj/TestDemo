package com.clean.videocompress.api.model

import android.net.Uri

data class VideoCompressResult(
    val sourceAsset: CompressVideoAsset,
    val outputUri: Uri,
    val outputSizeBytes: Long,
    val savedBytes: Long,
    val elapsedMs: Long
)
