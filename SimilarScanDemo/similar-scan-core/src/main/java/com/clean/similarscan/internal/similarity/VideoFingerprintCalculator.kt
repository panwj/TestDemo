package com.clean.similarscan.internal.similarity

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.clean.similarscan.internal.model.MediaAsset
import java.io.File
import kotlin.math.abs

/**
 * 按竞品规则提取视频多帧指纹。
 *
 * 竞品 ExtractionRule 为：最小间隔 2、最大间隔 10、常规 7 帧、最多 13 帧。
 *
 * FAST/BALANCED 优先利用系统缩略图降低成本；COMPETITOR_COMPAT 不混入系统
 * 缩略图，按竞品 0..duration 的 7 到 13 帧规则生成指纹。
 */
internal class VideoFingerprintCalculator(context: Context) {
    private val appContext = context.applicationContext

    fun calculate(
        asset: MediaAsset,
        mode: VideoFingerprintMode = VideoFingerprintMode.BALANCED
    ): VideoFingerprint {
        val shouldLoadSystemThumbnail = mode != VideoFingerprintMode.COMPETITOR_COMPAT
        val thumbnailHash = if (shouldLoadSystemThumbnail) systemVideoThumbnail(asset)?.let { thumbnail ->
            try {
                HashCalculator.buildHash(thumbnail)
            } finally {
                thumbnail.recycle()
            }
        } else {
            null
        }
        if (mode == VideoFingerprintMode.FAST && thumbnailHash?.isValid() == true) {
            return VideoFingerprint(
                frames = listOf(thumbnailHash),
                qualityScore = MediaQualityAnalyzer.metadataScore(asset),
                source = VideoFingerprintSource.SYSTEM_THUMBNAIL
            )
        }

        val retriever = MediaMetadataRetriever()
        return try {
            if (!setRetrieverDataSource(retriever, asset)) {
                return if (thumbnailHash?.isValid() == true) {
                    VideoFingerprint(
                        frames = listOf(thumbnailHash),
                        qualityScore = MediaQualityAnalyzer.metadataScore(asset),
                        source = VideoFingerprintSource.SYSTEM_THUMBNAIL
                    )
                } else {
                    invalidFingerprint()
                }
            }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: asset.duration.toDouble()
            val sampleTimesMs = buildSampleTimes(durationMs, mode)
            val hashes = ArrayList<CombinedHash>(sampleTimesMs.size + 1)
            if (thumbnailHash?.isValid() == true && mode == VideoFingerprintMode.BALANCED) {
                hashes += thumbnailHash
            }
            var qualityScore = MediaQualityAnalyzer.metadataScore(asset)

            sampleTimesMs.forEach { timeMs ->
                val frame = extractFrame(retriever, (timeMs * MICROSECONDS_PER_MILLISECOND).toLong())
                if (frame == null) {
                    // 竞品会保留无效帧对象，不能因单帧失败改变后续最小命中数。
                    hashes += INVALID_HASH
                } else {
                    try {
                        hashes += if (mode.shouldFilterLowInformationFrames() && isLowInformationFrame(frame)) {
                            INVALID_HASH
                        } else {
                            HashCalculator.buildHash(frame)
                        }
                        /*
                         * 视频抽出的帧已经是 9x8 小图，质量分只做元数据排序兜底。
                         * 对这种小图再做 64x64 放大采样没有意义，也会放大扫描成本。
                         */
                        qualityScore = maxOf(qualityScore, MediaQualityAnalyzer.metadataScore(asset))
                    } finally {
                        frame.recycle()
                    }
                }
            }
            val source = videoFingerprintSource(
                frames = hashes,
                hasSystemThumbnail = thumbnailHash?.isValid() == true,
                mode = mode
            )
            VideoFingerprint(
                frames = hashes.ifEmpty { listOf(INVALID_HASH) },
                qualityScore = qualityScore,
                source = source
            )
        } catch (_: RuntimeException) {
            if (thumbnailHash?.isValid() == true) {
                VideoFingerprint(
                    frames = listOf(thumbnailHash),
                    qualityScore = MediaQualityAnalyzer.metadataScore(asset),
                    source = VideoFingerprintSource.SYSTEM_THUMBNAIL
                )
            } else {
                invalidFingerprint()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 优先读取系统维护的视频缩略图。
     *
     * API 29+ 使用 ContentResolver.loadThumbnail；API 23-28 使用旧版
     * MediaStore.Video.Thumbnails。两者都可能命中系统/相册预热缓存，明显快于逐视频
     * MediaMetadataRetriever 多帧抽取。
     */
    private fun systemVideoThumbnail(asset: MediaAsset): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return appContext.contentResolver.loadThumbnail(
                    asset.uri,
                    Size(SYSTEM_THUMBNAIL_SIZE, SYSTEM_THUMBNAIL_SIZE),
                    null
                )
            } catch (_: Exception) {
                // 系统没有缩略图或权限受限时继续尝试旧接口/自抽帧。
            }
        }
        return try {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                appContext.contentResolver,
                asset.id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 复现竞品传入真实文件路径的方式；无法读取 DATA 时直接生成无效指纹。
     */
    @Suppress("DEPRECATION")
    private fun mediaPath(asset: MediaAsset): String? {
        return appContext.contentResolver.query(
            asset.uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }

    private fun setRetrieverDataSource(
        retriever: MediaMetadataRetriever,
        asset: MediaAsset
    ): Boolean {
        mediaPath(asset)?.let { path ->
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return try {
                    retriever.setDataSource(path)
                    true
                } catch (_: RuntimeException) {
                    false
                }
            }
        }
        return try {
            appContext.contentResolver.openFileDescriptor(asset.uri, "r")?.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 7 帧等距采样覆盖完整时间轴。
     *
     * 3 帧 quick 预筛在真实数据上会漏掉相似视频；13 帧完整规则成本又过高。
     * 当前选择 7 帧作为稳定折中：比 quick 有更好的召回，比 13 帧少接近一半抽帧。
     */
    private fun buildSampleTimes(durationMs: Double, mode: VideoFingerprintMode): List<Double> {
        if (durationMs <= 0.0) return listOf(0.0)
        val positions = when (mode) {
            VideoFingerprintMode.FAST -> FAST_FALLBACK_POSITIONS
            VideoFingerprintMode.BALANCED -> BALANCED_POSITIONS
            VideoFingerprintMode.ACCURATE -> ACCURATE_POSITIONS
            VideoFingerprintMode.COMPETITOR_COMPAT -> return buildCompetitorSampleTimes(durationMs)
        }
        return positions.map { durationMs * it }.distinct()
    }

    private fun buildCompetitorSampleTimes(durationMs: Double): List<Double> {
        val duration = durationMs.toLong().coerceAtLeast(0L)
        if (duration <= 0L) return listOf(0.0)
        val sevenFrameInterval = duration.toDouble() / (COMPETITOR_FRAME_COUNT - 1).toDouble()
        val times = when {
            sevenFrameInterval in COMPETITOR_MIN_INTERVAL_MS..COMPETITOR_MAX_INTERVAL_MS -> {
                buildEvenlySpacedTimes(duration, COMPETITOR_FRAME_COUNT)
            }
            sevenFrameInterval < COMPETITOR_MIN_INTERVAL_MS -> {
                buildIntervalTimes(duration, COMPETITOR_MIN_INTERVAL_MS.toLong())
            }
            else -> {
                buildIntervalTimes(duration, COMPETITOR_MAX_INTERVAL_MS.toLong())
                    .let { times ->
                        if (times.size <= COMPETITOR_MAX_FRAME_COUNT) {
                            times
                        } else {
                            buildEvenlySpacedTimes(duration, COMPETITOR_MAX_FRAME_COUNT)
                        }
                    }
            }
        }
        return times.distinct().map(Long::toDouble)
    }

    private fun buildEvenlySpacedTimes(durationMs: Long, frameCount: Int): List<Long> {
        val count = frameCount.coerceAtLeast(2)
        val interval = durationMs.toDouble() / (count - 1).toDouble()
        return (0 until count).map { index ->
            kotlin.math.round(index * interval).toLong().coerceAtMost(durationMs)
        }
    }

    private fun buildIntervalTimes(durationMs: Long, intervalMs: Long): List<Long> {
        val safeInterval = intervalMs.coerceAtLeast(1L)
        val times = ArrayList<Long>()
        var time = 0L
        while (time < durationMs) {
            times += time
            time += safeInterval
        }
        if (times.isEmpty() || times.last() != durationMs) {
            times += durationMs
        }
        return times
    }

    private fun extractFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return when {
            Build.VERSION.SDK_INT >= 30 -> {
                /*
                 * API 30+ 与竞品完全相同：指定 ARGB_8888，并由系统直接输出 9x8 帧。
                 */
                val params = MediaMetadataRetriever.BitmapParams().apply {
                    preferredConfig = Bitmap.Config.ARGB_8888
                }
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    HASH_WIDTH,
                    HASH_HEIGHT,
                    params
                )
            }

            Build.VERSION.SDK_INT >= 27 -> {
                /*
                 * API 27-29 没有 BitmapParams 重载，但支持系统缩放抽帧。
                 * HashCalculator 会在计算前兼容非 ARGB_8888 Bitmap。
                 */
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    HASH_WIDTH,
                    HASH_HEIGHT
                )
            }

            else -> {
                /*
                 * API 23-26 只能先获取关键帧，再缩放到竞品使用的 9x8 输入尺寸。
                 */
                retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )?.let { source ->
                    val scaled = Bitmap.createScaledBitmap(
                        source,
                        HASH_WIDTH,
                        HASH_HEIGHT,
                        true
                    )
                    if (scaled !== source) source.recycle()
                    scaled
                }
            }
        }
    }

    private fun isLowInformationFrame(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        var minLuma = 255
        var maxLuma = 0
        var edgeEnergy = 0L
        var previous = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val luma = luminance(bitmap.getPixel(x, y))
                minLuma = minOf(minLuma, luma)
                maxLuma = maxOf(maxLuma, luma)
                if (previous >= 0) edgeEnergy += abs(luma - previous)
                previous = luma
            }
        }
        val range = maxLuma - minLuma
        val averageEdge = edgeEnergy.toDouble() / maxOf(1, width * height - 1)
        return range < LOW_INFORMATION_LUMA_RANGE && averageEdge < LOW_INFORMATION_EDGE_AVERAGE
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun videoFingerprintSource(
        frames: List<CombinedHash>,
        hasSystemThumbnail: Boolean,
        mode: VideoFingerprintMode
    ): VideoFingerprintSource {
        val validFrameCount = frames.count(CombinedHash::isValid)
        val validMmrFrameCount = if (hasSystemThumbnail && mode == VideoFingerprintMode.BALANCED) {
            frames.drop(1).count(CombinedHash::isValid)
        } else {
            validFrameCount
        }
        return when {
            mode == VideoFingerprintMode.COMPETITOR_COMPAT -> VideoFingerprintSource.COMPETITOR_FRAMES
            hasSystemThumbnail && validMmrFrameCount == 0 -> VideoFingerprintSource.SYSTEM_THUMBNAIL
            hasSystemThumbnail && mode == VideoFingerprintMode.BALANCED -> VideoFingerprintSource.HYBRID_THUMBNAIL_AND_FRAMES
            else -> VideoFingerprintSource.MMR_FRAMES
        }
    }

    private fun VideoFingerprintMode.shouldFilterLowInformationFrames(): Boolean {
        return this != VideoFingerprintMode.COMPETITOR_COMPAT
    }

    private fun invalidFingerprint() = VideoFingerprint(
        frames = listOf(INVALID_HASH),
        source = VideoFingerprintSource.MMR_FRAMES
    )

    private companion object {
        const val MICROSECONDS_PER_MILLISECOND = 1_000.0
        const val SYSTEM_THUMBNAIL_SIZE = 512
        const val HASH_WIDTH = 9
        const val HASH_HEIGHT = 8
        const val LOW_INFORMATION_LUMA_RANGE = 8
        const val LOW_INFORMATION_EDGE_AVERAGE = 2.0
        const val COMPETITOR_FRAME_COUNT = 7
        const val COMPETITOR_MAX_FRAME_COUNT = 13
        const val COMPETITOR_MIN_INTERVAL_MS = 2_000.0
        const val COMPETITOR_MAX_INTERVAL_MS = 10_000.0
        val FAST_FALLBACK_POSITIONS = listOf(0.10, 0.50, 0.90)
        val BALANCED_POSITIONS = listOf(0.12, 0.38, 0.64, 0.88)
        val ACCURATE_POSITIONS = listOf(0.08, 0.22, 0.36, 0.50, 0.64, 0.78, 0.92)
        val INVALID_HASH = CombinedHash(-1L, emptyArray())
    }
}
