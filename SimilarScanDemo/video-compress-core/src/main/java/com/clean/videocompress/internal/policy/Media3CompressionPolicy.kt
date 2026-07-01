package com.clean.videocompress.internal.policy

import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressOption
import com.clean.videocompress.internal.util.BitrateCalculator
import java.io.File
import kotlin.math.max

/**
 * Media3 默认生产方案的压缩策略。
 *
 * 这里放“是否值得压缩、目标码率、是否降分辨率、结果校验”等通用产品策略，
 * 保持 Media3 引擎主体只关心 Transformer 调用。
 */
internal object Media3CompressionPolicy {
    fun buildPlan(asset: CompressVideoAsset, option: VideoCompressOption): Media3CompressionPlan {
        val sourceBitrate = BitrateCalculator.sourceBitrate(asset)
        val dynamicRate = dynamicCompressionRate(asset, option, sourceBitrate)
        val targetBitrate = BitrateCalculator.targetBitrate(asset, dynamicRate)
        val targetHeight = targetHeight(asset)
        val estimatedOutputSize = estimateOutputSize(asset, sourceBitrate, targetBitrate, targetHeight)
        val estimatedSaving = (asset.sizeBytes - estimatedOutputSize).coerceAtLeast(0L)
        val rejectReason = rejectReason(asset, sourceBitrate, estimatedSaving)
        return Media3CompressionPlan(
            sourceBitrate = sourceBitrate,
            targetBitrate = targetBitrate,
            dynamicCompressionRatePercent = dynamicRate,
            targetHeight = targetHeight,
            estimatedOutputSizeBytes = estimatedOutputSize,
            estimatedSavingBytes = estimatedSaving,
            rejectReason = rejectReason
        )
    }

    fun validateResult(asset: CompressVideoAsset, outputFile: File): String? {
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            return "Output file is empty."
        }
        if (outputFile.length() >= asset.sizeBytes * MAX_OUTPUT_SIZE_RATIO) {
            return "Compressed file is not smaller enough than the source."
        }
        return null
    }

    private fun rejectReason(
        asset: CompressVideoAsset,
        sourceBitrate: Int,
        estimatedSaving: Long
    ): String? {
        if (asset.durationMs in 1 until MIN_DURATION_MS) {
            return "Video is too short to compress efficiently."
        }
        if (sourceBitrate in 1 until MIN_SOURCE_BITRATE) {
            return "Video bitrate is already low."
        }
        if (asset.sizeBytes in 1 until MIN_SOURCE_SIZE_BYTES) {
            return "Video file is too small to produce meaningful savings."
        }
        if (estimatedSaving in 0 until MIN_ESTIMATED_SAVING_BYTES) {
            return "Estimated saving is too small."
        }
        return null
    }

    private fun dynamicCompressionRate(
        asset: CompressVideoAsset,
        option: VideoCompressOption,
        sourceBitrate: Int
    ): Int {
        var rate = option.compressionRatePercent.coerceIn(10, 85)
        val longEdge = max(asset.width, asset.height)
        if (longEdge >= 3840) rate += 10
        if (sourceBitrate > 12_000_000) rate += 10
        if (sourceBitrate in 1..2_000_000) rate -= 15
        if (asset.durationMs in 1 until 10_000L) rate -= 10
        return rate.coerceIn(10, 85)
    }

    private fun targetHeight(asset: CompressVideoAsset): Int? {
        val longEdge = max(asset.width, asset.height)
        val shortEdge = minOf(asset.width, asset.height)
        return when {
            longEdge >= 3840 && shortEdge > 1080 -> 1080
            longEdge >= 2560 && shortEdge > 1080 -> 1080
            longEdge >= 1920 && shortEdge > 720 -> 720
            else -> null
        }
    }

    private fun estimateOutputSize(
        asset: CompressVideoAsset,
        sourceBitrate: Int,
        targetBitrate: Int,
        targetHeight: Int?
    ): Long {
        val bitrateRatio = if (sourceBitrate > 0) {
            targetBitrate.toDouble() / sourceBitrate.toDouble()
        } else {
            1.0
        }
        val scaleRatio = if (targetHeight != null) {
            val shortEdge = minOf(asset.width, asset.height).coerceAtLeast(1)
            (targetHeight.toDouble() / shortEdge.toDouble()).coerceAtMost(1.0)
        } else {
            1.0
        }
        return (asset.sizeBytes * bitrateRatio * scaleRatio).toLong().coerceAtLeast(0L)
    }

    private const val MIN_DURATION_MS = 3_000L
    private const val MIN_SOURCE_BITRATE = 900_000
    private const val MIN_SOURCE_SIZE_BYTES = 3L * 1024L * 1024L
    private const val MIN_ESTIMATED_SAVING_BYTES = 1L * 1024L * 1024L
    private const val MAX_OUTPUT_SIZE_RATIO = 0.98
}

internal data class Media3CompressionPlan(
    val sourceBitrate: Int,
    val targetBitrate: Int,
    val dynamicCompressionRatePercent: Int,
    val targetHeight: Int?,
    val estimatedOutputSizeBytes: Long,
    val estimatedSavingBytes: Long,
    val rejectReason: String?
)
