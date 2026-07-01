package com.clean.videocompress.internal.media

import com.clean.videocompress.api.VideoCompressConfig
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoBucket

internal class VideoBucketBuilder(private val config: VideoCompressConfig) {
    fun build(videos: List<CompressVideoAsset>): List<VideoBucket> {
        if (videos.isEmpty()) return emptyList()
        val mediumRate = config.options
            .firstOrNull { it.key == "medium" }
            ?.compressionRatePercent
            ?: 50
        val remaining = videos.sortedByDescending { estimateSaving(it, mediumRate) }.toMutableList()
        return config.bucketRules.mapNotNull { rule ->
            val matched = remaining.filter { estimateSaving(it, mediumRate) >= rule.minEstimatedSavingBytes }
            remaining.removeAll(matched.toSet())
            if (matched.isEmpty()) {
                null
            } else {
                VideoBucket(
                    key = rule.key,
                    title = rule.title,
                    subtitle = rule.subtitle,
                    color = rule.color,
                    videos = matched.sortedByDescending { it.sizeBytes },
                    totalBytes = matched.sumOf { it.sizeBytes },
                    estimatedSavingBytes = matched.sumOf { estimateSaving(it, mediumRate) }
                )
            }
        }
    }

    private fun estimateSaving(asset: CompressVideoAsset, ratePercent: Int): Long {
        return (asset.sizeBytes * ratePercent.coerceIn(0, 95) / 100L).coerceAtLeast(0L)
    }
}
