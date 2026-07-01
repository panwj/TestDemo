package com.clean.videocompress.internal.util

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressOption

internal object BitrateCalculator {
    fun targetBitrate(asset: CompressVideoAsset, option: VideoCompressOption): Int {
        val sourceBitrate = when {
            asset.bitrate > 0 -> asset.bitrate
            asset.durationMs > 0 -> ((asset.sizeBytes * 8_000L) / asset.durationMs).toInt()
            else -> 2_000_000
        }
        val keepRatio = (100 - option.compressionRatePercent.coerceIn(0, 95)) / 100f
        return (sourceBitrate * keepRatio).toInt().coerceAtLeast(350_000)
    }

    fun estimateOutputSize(asset: CompressVideoAsset, option: VideoCompressOption): Long {
        return (asset.sizeBytes * (100 - option.compressionRatePercent.coerceIn(0, 95)) / 100L)
            .coerceAtLeast(0L)
    }
}
