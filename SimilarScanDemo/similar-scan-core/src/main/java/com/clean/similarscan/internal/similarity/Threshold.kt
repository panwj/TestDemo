package com.clean.similarscan.internal.similarity

import com.clean.similarscan.internal.model.MediaKind

/**
 * 分媒体类型阈值。
 *
 * 图片使用宽松阈值；截图、普通视频和录屏统一使用严格参数，以降低视频/录屏大组误合并。
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
         * 普通照片使用的阈值。
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
         * 严格视频阈值。
         *
         * dHash 直接命中只允许 0..2，中间距离再用更小的 colorHash 距离过滤，
         * 适合画面结构重复度较高的截图/录屏类内容。
         */
        private val strictVideoLikeThreshold = Threshold(
            directImageDistance = 0L..2L,
            maxImageDistanceExclusive = 16L,
            colorRanges = listOf(
                ColorRange(2L..10L, 0L..5L),
                ColorRange(10L..16L, 0L..2L)
            )
        )

        /**
         * 普通视频阈值。当前使用严格视频类参数，避免静止画面或相同片头导致过度合并。
         */
        private val videoThreshold = strictVideoLikeThreshold

        /**
         * 截图阈值。
         */
        private val screenshotThreshold = strictVideoLikeThreshold

        /**
         * 录屏阈值。
         *
         * 录屏常包含大片静止 UI、开场动画和重复转场，如果使用普通照片阈值，
         * 容易把只存在局部相似的录屏合并到同一组。这里改为严格阈值，
         * 与截图/严格视频类内容保持一致，降低误合并。
         */
        private val screenRecordingThreshold = strictVideoLikeThreshold

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
