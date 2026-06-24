package com.example.similarscandemo.similarity

import com.example.similarscandemo.model.MediaKind

/**
 * 与竞品保持一致的分类型阈值。
 *
 * directImageDistance 表示 dHash 汉明距离足够小时直接判相似；
 * colorRanges 表示 dHash 处于中间区间时，再用 colorHash 过滤误判。
 */
data class Threshold(
    val directImageDistance: LongRange,
    val maxImageDistanceExclusive: Long,
    val colorRanges: List<ColorRange>
) {
    companion object {
        /**
         * 竞品 qh.a：普通照片使用的阈值。
         */
        private val photoThreshold = Threshold(
            directImageDistance = 0L..4L,
            maxImageDistanceExclusive = 18L,
            colorRanges = listOf(
                ColorRange(4L..10L, 0L..7L),
                ColorRange(10L..18L, 0L..5L)
            )
        )

        /**
         * 竞品 qh.b：视频使用更严格的阈值。
         */
        private val videoThreshold = Threshold(
            directImageDistance = 0L..2L,
            maxImageDistanceExclusive = 16L,
            colorRanges = listOf(
                ColorRange(2L..10L, 0L..5L),
                ColorRange(10L..16L, 0L..2L)
            )
        )

        /**
         * 竞品 qh.c：截图使用的阈值，比照片更严格。
         */
        private val screenshotThreshold = Threshold(
            directImageDistance = 0L..2L,
            maxImageDistanceExclusive = 16L,
            colorRanges = listOf(
                ColorRange(2L..10L, 0L..5L),
                ColorRange(10L..16L, 0L..2L)
            )
        )

        /**
         * 录屏使用的阈值，与普通照片相同。
         */
        private val screenRecordingThreshold = Threshold(
            directImageDistance = 0L..4L,
            maxImageDistanceExclusive = 18L,
            colorRanges = listOf(
                ColorRange(4L..10L, 0L..7L),
                ColorRange(10L..18L, 0L..5L)
            )
        )

        fun forKind(kind: MediaKind): Threshold {
            return when (kind) {
                MediaKind.PHOTO -> photoThreshold
                MediaKind.VIDEO -> videoThreshold
                MediaKind.SCREENSHOT -> screenshotThreshold
                MediaKind.SCREEN_RECORDING -> screenRecordingThreshold
            }
        }

        /**
         * 候选索引使用的最大汉明距离。
         *
         * 最终判断条件是 distance < maxImageDistanceExclusive，因此索引查询上限需要减一。
         */
        fun maxCandidateDistance(kind: MediaKind): Int {
            return (forKind(kind).maxImageDistanceExclusive - 1L).toInt()
        }
    }
}

data class ColorRange(
    val imageDistance: LongRange,
    val colorDistance: LongRange
)
