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
    /**
     * 生成一次 Media3 压缩计划。
     *
     * 计划包含目标码率、是否降分辨率、预计节省空间、是否拒绝压缩等信息。
     */
    fun buildPlan(
        asset: CompressVideoAsset,
        option: VideoCompressOption,
        profile: VideoFormatProfile = VideoFormatProfile()
    ): Media3CompressionPlan {
        val sourceBitrate = sourceBitrate(asset, profile)
        val sourceWidth = sourceWidth(asset, profile)
        val sourceHeight = sourceHeight(asset, profile)
        val sourceFrameRate = sourceFrameRate(profile)
        val dynamicRate = dynamicCompressionRate(
            asset = asset,
            option = option,
            sourceBitrate = sourceBitrate,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceFrameRate = sourceFrameRate,
            profile = profile
        )
        val targetHeight = targetHeight(sourceWidth, sourceHeight)
        val targetBitrate = targetBitrate(
            sourceBitrate = sourceBitrate,
            compressionRatePercent = dynamicRate,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceFrameRate = sourceFrameRate,
            targetHeight = targetHeight,
            profile = profile
        )
        val estimatedOutputSize = estimateOutputSize(
            asset = asset,
            sourceBitrate = sourceBitrate,
            targetBitrate = targetBitrate,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetHeight = targetHeight
        )
        val estimatedSaving = (asset.sizeBytes - estimatedOutputSize).coerceAtLeast(0L)
        val rejectReason = rejectReason(asset, sourceBitrate, estimatedSaving, profile)
        return Media3CompressionPlan(
            sourceBitrate = sourceBitrate,
            targetBitrate = targetBitrate,
            dynamicCompressionRatePercent = dynamicRate,
            targetHeight = targetHeight,
            estimatedOutputSizeBytes = estimatedOutputSize,
            estimatedSavingBytes = estimatedSaving,
            rejectReason = rejectReason,
            sourceVideoMime = profile.videoMime,
            isHevcSource = profile.isHevc,
            sourceFrameRate = sourceFrameRate,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            isMeasuredMetadata = profile.bitrate > 0 || profile.frameRate > 0
        )
    }

    /**
     * 校验压缩后的临时文件是否有基本收益。
     */
    fun validateResult(asset: CompressVideoAsset, outputFile: File): String? {
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            return "Output file is empty."
        }
        if (outputFile.length() >= asset.sizeBytes * MAX_OUTPUT_SIZE_RATIO) {
            return "Compressed file is not smaller enough than the source."
        }
        return null
    }

    /**
     * 判断当前视频是否不适合继续压缩。
     */
    private fun rejectReason(
        asset: CompressVideoAsset,
        sourceBitrate: Int,
        estimatedSaving: Long,
        profile: VideoFormatProfile
    ): String? {
        if (profile.isHdr) {
            return "HDR video is not compressed by default to avoid color distortion."
        }
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

    /**
     * 根据视频尺寸、码率、帧率、格式和时长动态调整压缩比例。
     *
     * 压缩比例越大，目标码率越低；HEVC、高帧率、短视频会更保守。
     */
    private fun dynamicCompressionRate(
        asset: CompressVideoAsset,
        option: VideoCompressOption,
        sourceBitrate: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFrameRate: Int,
        profile: VideoFormatProfile
    ): Int {
        var rate = option.compressionRatePercent.coerceIn(10, 85)
        val longEdge = max(sourceWidth, sourceHeight)
        if (longEdge >= 3840) {
            rate += 12
        } else if (longEdge >= 2560) {
            rate += 8
        } else if (longEdge >= 1920) {
            rate += 4
        }
        when {
            sourceBitrate > 20_000_000 -> rate += 12
            sourceBitrate > 12_000_000 -> rate += 8
            sourceBitrate > 8_000_000 -> rate += 4
            sourceBitrate in 1..2_500_000 -> rate -= 18
            sourceBitrate in 2_500_001..4_000_000 -> rate -= 8
        }
        if (sourceFrameRate >= 60) {
            rate -= 6
        } else if (sourceFrameRate >= 45) {
            rate -= 3
        }
        if (profile.isHevc) {
            rate -= 5
        }
        if (asset.durationMs in 1 until 10_000L) rate -= 10
        return rate.coerceIn(10, 85)
    }

    /**
     * 决定是否需要降分辨率。
     */
    private fun targetHeight(sourceWidth: Int, sourceHeight: Int): Int? {
        val longEdge = max(sourceWidth, sourceHeight)
        val shortEdge = minOf(sourceWidth, sourceHeight)
        return when {
            longEdge >= 3840 && shortEdge > 1080 -> 1080
            longEdge >= 2560 && shortEdge > 1080 -> 1080
            longEdge >= 1920 && shortEdge > 720 -> 720
            else -> null
        }
    }

    /**
     * 获取真实源码率。优先使用压缩前读取的 MediaFormat 码率。
     */
    private fun sourceBitrate(asset: CompressVideoAsset, profile: VideoFormatProfile): Int {
        return if (profile.bitrate > 0) profile.bitrate else BitrateCalculator.sourceBitrate(asset)
    }

    /**
     * 获取源视频帧率，异常值统一回退到 30fps。
     */
    private fun sourceFrameRate(profile: VideoFormatProfile): Int {
        return profile.frameRate.takeIf { it in 1..MAX_REASONABLE_FRAME_RATE } ?: DEFAULT_FRAME_RATE
    }

    /**
     * 结合 rotation 修正源视频展示宽度。
     */
    private fun sourceWidth(asset: CompressVideoAsset, profile: VideoFormatProfile): Int {
        val width = profile.width.takeIf { it > 0 } ?: asset.width
        val height = profile.height.takeIf { it > 0 } ?: asset.height
        return if (profile.rotationDegrees % 180 != 0) height else width
    }

    /**
     * 结合 rotation 修正源视频展示高度。
     */
    private fun sourceHeight(asset: CompressVideoAsset, profile: VideoFormatProfile): Int {
        val width = profile.width.takeIf { it > 0 } ?: asset.width
        val height = profile.height.takeIf { it > 0 } ?: asset.height
        return if (profile.rotationDegrees % 180 != 0) width else height
    }

    /**
     * 计算最终目标码率。
     *
     * 先按用户档位计算基础目标码率，再用分辨率/帧率/HEVC 质量下限保护画质，
     * 同时限制不超过原始码率的 92%，避免压缩收益过低。
     */
    private fun targetBitrate(
        sourceBitrate: Int,
        compressionRatePercent: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFrameRate: Int,
        targetHeight: Int?,
        profile: VideoFormatProfile
    ): Int {
        val keepRatio = (100 - compressionRatePercent.coerceIn(0, 95)) / 100f
        val requested = (sourceBitrate * keepRatio).toInt().coerceAtLeast(MIN_TARGET_BITRATE)
        val floor = qualityBitrateFloor(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceFrameRate = sourceFrameRate,
            targetHeight = targetHeight,
            isHevcSource = profile.isHevc
        )
        val maxTarget = (sourceBitrate * MAX_TARGET_BITRATE_RATIO).toInt()
            .coerceAtLeast(MIN_TARGET_BITRATE)
        val boundedFloor = floor.coerceAtMost(maxTarget)
        return requested.coerceIn(boundedFloor, maxTarget)
    }

    /**
     * 按分辨率档位计算码率保护下限。
     */
    private fun qualityBitrateFloor(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFrameRate: Int,
        targetHeight: Int?,
        isHevcSource: Boolean
    ): Int {
        val sourceLongEdge = max(sourceWidth, sourceHeight)
        val outputShortEdge = targetHeight ?: minOf(sourceWidth, sourceHeight).coerceAtLeast(1)
        var floor = when {
            sourceLongEdge >= 3840 -> BITRATE_FLOOR_4K_SOURCE
            outputShortEdge >= 1080 -> BITRATE_FLOOR_1080P
            outputShortEdge >= 720 -> BITRATE_FLOOR_720P
            outputShortEdge >= 540 -> BITRATE_FLOOR_540P
            else -> BITRATE_FLOOR_LOW_RESOLUTION
        }
        if (sourceFrameRate >= 60) {
            floor = (floor * 1.35f).toInt()
        } else if (sourceFrameRate >= 45) {
            floor = (floor * 1.18f).toInt()
        }
        if (isHevcSource) {
            floor = (floor * 1.15f).toInt()
        }
        return floor.coerceAtLeast(MIN_TARGET_BITRATE)
    }

    /**
     * 根据码率变化和分辨率缩放估算输出大小，用于空间检查和“不值得压缩”判断。
     */
    private fun estimateOutputSize(
        asset: CompressVideoAsset,
        sourceBitrate: Int,
        targetBitrate: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        targetHeight: Int?
    ): Long {
        val bitrateRatio = if (sourceBitrate > 0) {
            targetBitrate.toDouble() / sourceBitrate.toDouble()
        } else {
            1.0
        }
        val scaleRatio = if (targetHeight != null) {
            val shortEdge = minOf(sourceWidth, sourceHeight).coerceAtLeast(1)
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
    private const val MIN_TARGET_BITRATE = 350_000
    private const val MAX_TARGET_BITRATE_RATIO = 0.92f
    private const val DEFAULT_FRAME_RATE = 30
    private const val MAX_REASONABLE_FRAME_RATE = 240
    private const val BITRATE_FLOOR_4K_SOURCE = 6_000_000
    private const val BITRATE_FLOOR_1080P = 4_500_000
    private const val BITRATE_FLOOR_720P = 2_500_000
    private const val BITRATE_FLOOR_540P = 1_400_000
    private const val BITRATE_FLOOR_LOW_RESOLUTION = 800_000
}

/**
 * Media3 压缩计划。
 */
internal data class Media3CompressionPlan(
    val sourceBitrate: Int,
    val targetBitrate: Int,
    val dynamicCompressionRatePercent: Int,
    val targetHeight: Int?,
    val estimatedOutputSizeBytes: Long,
    val estimatedSavingBytes: Long,
    val rejectReason: String?,
    val sourceVideoMime: String = "",
    val isHevcSource: Boolean = false,
    val sourceFrameRate: Int = 30,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val isMeasuredMetadata: Boolean = false
)
