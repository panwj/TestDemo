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

    fun isSimilarTo(other: VideoFingerprint, kind: MediaKind): Boolean {
        if (!isValid() || !other.isValid()) return false

        /*
         * 竞品使用多帧匹配：遍历抽出的帧组合，只要有足够多的帧相似即判定视频相似。
         * 这里的 kind 决定每一帧使用普通视频还是录屏阈值，避免录屏误用普通视频参数。
         */
        var matchedCount = 0
        for (frame in frames) {
            if (!frame.isValid()) continue
            for (candidate in other.frames) {
                if (!candidate.isValid()) continue
                if (frame.isSimilarTo(candidate, kind)) {
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
