package com.example.similarscandemo.similarity

import com.example.similarscandemo.model.MediaKind

/**
 * 视频视觉指纹。
 *
 * 竞品会按视频时长抽取 7～13 个归一化时间位置的帧。
 * 抽帧失败也会保留无效占位，因此这里只要求帧列表非空；无效帧在比较时跳过。
 */
data class VideoFingerprint(
    val frames: List<CombinedHash>,
    val qualityScore: Double = 0.0
) {
    fun isValid(): Boolean = frames.isNotEmpty()

    fun isSimilarTo(other: VideoFingerprint): Boolean {
        if (!isValid() || !other.isValid()) return false

        /*
         * 竞品使用全帧笛卡尔积匹配：遍历所有帧组合，只要有足够多的帧对相似即判定视频相似。
         * MIN_MATCHED_FRAME_COUNT=2 表示至少需要2对相似帧。
         */
        var matchedCount = 0
        for (frame in frames) {
            if (!frame.isValid()) continue
            for (candidate in other.frames) {
                if (!candidate.isValid()) continue
                if (frame.isSimilarTo(candidate, MediaKind.VIDEO)) {
                    matchedCount++
                    if (matchedCount >= MIN_MATCHED_FRAME_COUNT) return true
                    break
                }
            }
        }
        return false
    }

    private companion object {
        const val MIN_MATCHED_FRAME_COUNT = 2
    }
}
