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
         * Demo 在保持“至少两帧命中”的口径上增加两个工程约束：
         *
         * 1. 每个候选帧只能被消费一次，避免 A 的多个静态帧都命中 B 的同一张黑屏/片头；
         * 2. 命中的帧位置需要有一定间隔，避免只靠开头连续两帧相似就把整段视频合并。
         *
         * 这样不会降低正常重复录屏/重复视频的召回，但能减少“一个大组还能继续拆”的误合并。
         * kind 决定每一帧使用普通视频还是录屏阈值，避免录屏误用普通照片参数。
         */
        val usedCandidateIndexes = mutableSetOf<Int>()
        val matchedFrameIndexes = mutableListOf<Int>()
        for ((frameIndex, frame) in frames.withIndex()) {
            if (!frame.isValid()) continue
            for ((candidateIndex, candidate) in other.frames.withIndex()) {
                if (candidateIndex in usedCandidateIndexes) continue
                if (!candidate.isValid()) continue
                if (frame.isSimilarTo(candidate, kind)) {
                    usedCandidateIndexes += candidateIndex
                    matchedFrameIndexes += frameIndex
                    if (hasEnoughSeparatedMatches(matchedFrameIndexes)) return true
                    break
                }
            }
        }
        return false
    }

    private fun hasEnoughSeparatedMatches(indexes: List<Int>): Boolean {
        if (indexes.size < MIN_MATCHED_FRAME_COUNT) return false
        val first = indexes.first()
        return indexes.any { kotlin.math.abs(it - first) >= MIN_MATCHED_FRAME_GAP }
    }

    private companion object {
        const val MIN_MATCHED_FRAME_COUNT = 2
        const val MIN_MATCHED_FRAME_GAP = 2
    }
}
