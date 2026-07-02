package com.clean.videocompress.internal.util

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressOption

/**
 * 码率和体积估算工具。
 *
 * Media3 主链路已有更细的产品策略；这里保留通用估算能力，供 Native 备用方案、
 * 分桶和空间预估等轻量场景复用。
 */
internal object BitrateCalculator {
    /**
     * 根据压缩档位计算目标码率。
     */
    fun targetBitrate(asset: CompressVideoAsset, option: VideoCompressOption): Int {
        return targetBitrate(asset, option.compressionRatePercent)
    }

    /**
     * 根据压缩比例计算目标码率，最低保护 350 Kbps。
     */
    fun targetBitrate(asset: CompressVideoAsset, compressionRatePercent: Int): Int {
        val sourceBitrate = sourceBitrate(asset)
        val keepRatio = (100 - compressionRatePercent.coerceIn(0, 95)) / 100f
        return (sourceBitrate * keepRatio).toInt().coerceAtLeast(350_000)
    }

    /**
     * 获取源视频码率。
     *
     * 如果列表阶段没有真实码率，则用文件大小和时长估算。
     */
    fun sourceBitrate(asset: CompressVideoAsset): Int {
        return when {
            asset.bitrate > 0 -> asset.bitrate
            asset.durationMs > 0 -> ((asset.sizeBytes * 8_000L) / asset.durationMs).toInt()
            else -> 2_000_000
        }
    }

    /**
     * 粗略估算压缩后大小。
     */
    fun estimateOutputSize(asset: CompressVideoAsset, option: VideoCompressOption): Long {
        return (asset.sizeBytes * (100 - option.compressionRatePercent.coerceIn(0, 95)) / 100L)
            .coerceAtLeast(0L)
    }
}
