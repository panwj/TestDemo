package com.clean.videocompress.api.model

import android.net.Uri

data class CompressVideoAsset(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val bitrate: Int
)
