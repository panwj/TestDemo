package com.clean.videocompress.internal.util

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressOption

internal object BitrateCalculator {
    fun targetBitrate(asset: CompressVideoAsset, option: VideoCompressOption): Int {
        return targetBitrate(asset, option.compressionRatePercent)
    }

    fun targetBitrate(asset: CompressVideoAsset, compressionRatePercent: Int): Int {
        val sourceBitrate = sourceBitrate(asset)
        val keepRatio = (100 - compressionRatePercent.coerceIn(0, 95)) / 100f
        return (sourceBitrate * keepRatio).toInt().coerceAtLeast(350_000)
    }

    fun sourceBitrate(asset: CompressVideoAsset): Int {
        return when {
            asset.bitrate > 0 -> asset.bitrate
            asset.durationMs > 0 -> ((asset.sizeBytes * 8_000L) / asset.durationMs).toInt()
            else -> 2_000_000
        }
    }

    fun estimateOutputSize(asset: CompressVideoAsset, option: VideoCompressOption): Long {
        return (asset.sizeBytes * (100 - option.compressionRatePercent.coerceIn(0, 95)) / 100L)
            .coerceAtLeast(0L)
    }
}
