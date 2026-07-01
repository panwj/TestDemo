package com.clean.videocompress.api.model

data class VideoBucket(
    val key: String,
    val title: String,
    val subtitle: String,
    val color: Int,
    val videos: List<CompressVideoAsset>,
    val totalBytes: Long,
    val estimatedSavingBytes: Long
)
