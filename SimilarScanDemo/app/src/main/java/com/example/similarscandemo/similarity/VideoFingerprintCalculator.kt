package com.example.similarscandemo.similarity

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import com.example.similarscandemo.model.MediaAsset
import java.io.File

/**
 * 按竞品规则提取视频多帧指纹。
 *
 * 竞品 ExtractionRule 为：最小间隔 2、最大间隔 10、常规 7 帧、最多 13 帧。
 *
 * 真机验证表明，两阶段 quick/full 方案会漏掉真实相似视频，而且命中后补完整指纹
 * 反而让总耗时上升。因此当前恢复为单阶段 7 帧稳定路径：
 * 首帧、尾帧和中间等距帧覆盖完整时间轴，召回质量明显优于 3 帧 quick 预筛。
 */
class VideoFingerprintCalculator(context: Context) {
    private val appContext = context.applicationContext

    fun calculate(asset: MediaAsset): VideoFingerprint {
        val path = mediaPath(asset) ?: return invalidFingerprint()
        val file = File(path)
        if (!file.exists() || !file.canRead()) return invalidFingerprint()

        val retriever = MediaMetadataRetriever()
        return try {
            // 竞品只接受 DATA 真实路径，不在路径失败时回退 content URI。
            retriever.setDataSource(path)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: asset.duration.toDouble()
            val sampleTimesMs = buildSampleTimes(durationMs)
            val hashes = ArrayList<CombinedHash>(sampleTimesMs.size)
            var qualityScore = 0.0

            sampleTimesMs.forEach { timeMs ->
                val frame = extractFrame(retriever, (timeMs * MICROSECONDS_PER_MILLISECOND).toLong())
                if (frame == null) {
                    // 竞品会保留无效帧对象，不能因单帧失败改变后续最小命中数。
                    hashes += INVALID_HASH
                } else {
                    try {
                        hashes += HashCalculator.buildHash(frame)
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
            VideoFingerprint(hashes.ifEmpty { listOf(INVALID_HASH) }, qualityScore)
        } catch (_: RuntimeException) {
            invalidFingerprint()
        } finally {
            runCatching { retriever.release() }
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

    /**
     * 使用 7 帧等距采样覆盖完整时间轴。
     *
     * 3 帧 quick 预筛在真实数据上会漏掉相似视频；13 帧完整规则成本又过高。
     * 当前选择 7 帧作为稳定折中：比 quick 有更好的召回，比 13 帧少接近一半抽帧。
     */
    private fun buildSampleTimes(durationMs: Double): List<Double> {
        if (durationMs <= 0.0) return listOf(0.0)
        return evenlySpaced(durationMs, NORMAL_FRAME_COUNT)
    }

    private fun evenlySpaced(durationMs: Double, count: Int): List<Double> {
        if (count <= 1) return listOf(0.0)
        val interval = durationMs / (count - 1)
        return List(count) { index -> minOf(durationMs, interval * index) }
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

    private fun invalidFingerprint() = VideoFingerprint(listOf(INVALID_HASH))

    private companion object {
        const val NORMAL_FRAME_COUNT = 7
        const val MICROSECONDS_PER_MILLISECOND = 1_000.0
        const val HASH_WIDTH = 9
        const val HASH_HEIGHT = 8
        val INVALID_HASH = CombinedHash(-1L, emptyArray())
    }
}
