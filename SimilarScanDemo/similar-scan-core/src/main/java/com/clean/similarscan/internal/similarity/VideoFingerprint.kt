package com.clean.similarscan.internal.similarity

import com.clean.similarscan.internal.model.MediaKind

/**
 * 视频视觉指纹。
 *
 * 竞品会按视频时长抽取 7～13 个归一化时间位置的帧。
 * 抽帧失败也会保留无效占位，因此这里只要求帧列表非空；无效帧在比较时跳过。
 */
data class VideoFingerprint(
    val frames: List<CombinedHash>,
    val qualityScore: Double = 0.0,
    val source: VideoFingerprintSource = if (frames.count(CombinedHash::isValid) <= 1) {
        VideoFingerprintSource.SYSTEM_THUMBNAIL
    } else {
        VideoFingerprintSource.MMR_FRAMES
    }
) {
    fun isValid(): Boolean = frames.any(CombinedHash::isValid)

    fun isSimilarTo(other: VideoFingerprint, kind: MediaKind): Boolean {
        if (!isValid() || !other.isValid()) return false

        if (source == VideoFingerprintSource.COMPETITOR_FRAMES &&
            other.source == VideoFingerprintSource.COMPETITOR_FRAMES
        ) {
            return isCompetitorCompatibleSimilar(frames, other.frames, kind)
        }
        val validFrames = frames.filter(CombinedHash::isValid)
        val validOtherFrames = other.frames.filter(CombinedHash::isValid)
        val thisOnlyThumbnail = source == VideoFingerprintSource.SYSTEM_THUMBNAIL && validFrames.size == 1
        val otherOnlyThumbnail = other.source == VideoFingerprintSource.SYSTEM_THUMBNAIL && validOtherFrames.size == 1
        if (thisOnlyThumbnail || otherOnlyThumbnail) {
            return thisOnlyThumbnail && otherOnlyThumbnail &&
                validFrames.first().isSimilarTo(validOtherFrames.first(), kind)
        }

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

    private fun isCompetitorCompatibleSimilar(
        firstFrames: List<CombinedHash>,
        secondFrames: List<CombinedHash>,
        kind: MediaKind
    ): Boolean {
        val validFirstFrames = firstFrames
            .mapIndexedNotNull { index, hash -> if (hash.isValid()) IndexedFrame(index, hash) else null }
        val validSecondFrames = secondFrames
            .mapIndexedNotNull { index, hash -> if (hash.isValid()) IndexedFrame(index, hash) else null }
        val comparableFrameCount = minOf(validFirstFrames.size, validSecondFrames.size)
        if (comparableFrameCount == 0) return false

        val rule = CompetitorMatchRule.forKind(kind, comparableFrameCount)
        val possibleMatches = buildList {
            validFirstFrames.forEach { first ->
                validSecondFrames.forEach { second ->
                    if (first.hash.isSimilarTo(second.hash, kind)) {
                        add(
                            FrameMatch(
                                firstIndex = first.index,
                                secondIndex = second.index,
                                imageDistance = java.lang.Long.bitCount(first.hash.imageHash xor second.hash.imageHash),
                                colorDistance = first.hash.colorDistanceTo(second.hash)
                            )
                        )
                    }
                }
            }
        }.sortedWith(
            compareBy<FrameMatch> { it.imageDistance }
                .thenBy { it.colorDistance }
                .thenBy { kotlin.math.abs(it.firstIndex - it.secondIndex) }
        )
        if (possibleMatches.isEmpty()) return false

        /*
         * 竞品兼容模式仍然沿用 7～13 帧抽样，但不能只做“任意两帧命中”。
         * 真实录屏/视频里经常存在相似片头、黑屏、加载页或静态页面，如果允许同一候选帧
         * 被多次消费，或者命中都集中在开头几帧，就会把本应拆开的资源合成一个大组。
         *
         * 这里把比较升级为三层约束：
         * 1. 每个候选帧只能消费一次；
         * 2. 命中数量要达到有效帧比例要求；
         * 3. 多帧资源的命中位置要覆盖一定时间跨度。
         *
         * 这样不改变抽帧成本，也不影响真正重复视频的召回，但能让“明显还能继续细分”
         * 的大组在最终 anchor 分组时自然拆开。
         */
        val usedFirstIndexes = mutableSetOf<Int>()
        val usedSecondIndexes = mutableSetOf<Int>()
        val matchedFirstIndexes = mutableListOf<Int>()
        val matchedSecondIndexes = mutableListOf<Int>()
        possibleMatches.forEach { match ->
            if (match.firstIndex in usedFirstIndexes || match.secondIndex in usedSecondIndexes) {
                return@forEach
            }
            usedFirstIndexes += match.firstIndex
            usedSecondIndexes += match.secondIndex
            matchedFirstIndexes += match.firstIndex
            matchedSecondIndexes += match.secondIndex

            if (matchesCompetitorRule(matchedFirstIndexes, matchedSecondIndexes, rule)) return true
        }
        return matchesCompetitorRule(matchedFirstIndexes, matchedSecondIndexes, rule)
    }

    private fun hasEnoughSeparatedMatches(indexes: List<Int>): Boolean {
        if (indexes.size < MIN_MATCHED_FRAME_COUNT) return false
        val first = indexes.first()
        return indexes.any { kotlin.math.abs(it - first) >= MIN_MATCHED_FRAME_GAP }
    }

    private fun matchesCompetitorRule(
        firstIndexes: List<Int>,
        secondIndexes: List<Int>,
        rule: CompetitorMatchRule
    ): Boolean {
        if (firstIndexes.size < rule.requiredMatches) return false
        if (rule.requiredSpan <= 0) return true
        return frameSpan(firstIndexes) >= rule.requiredSpan &&
            frameSpan(secondIndexes) >= rule.requiredSpan
    }

    private fun frameSpan(indexes: List<Int>): Int {
        if (indexes.isEmpty()) return 0
        return indexes.maxOrNull()!! - indexes.minOrNull()!!
    }

    private data class IndexedFrame(
        val index: Int,
        val hash: CombinedHash
    )

    private data class FrameMatch(
        val firstIndex: Int,
        val secondIndex: Int,
        val imageDistance: Int,
        val colorDistance: Long
    )

    private data class CompetitorMatchRule(
        val requiredMatches: Int,
        val requiredSpan: Int
    ) {
        companion object {
            fun forKind(kind: MediaKind, comparableFrameCount: Int): CompetitorMatchRule {
                if (comparableFrameCount <= 2) {
                    return CompetitorMatchRule(requiredMatches = 1, requiredSpan = 0)
                }
                if (comparableFrameCount <= 5) {
                    return CompetitorMatchRule(requiredMatches = 2, requiredSpan = 1)
                }

                val ratioPercent = if (kind == MediaKind.SCREEN_RECORDING) {
                    SCREEN_RECORDING_MATCH_RATIO_PERCENT
                } else {
                    VIDEO_MATCH_RATIO_PERCENT
                }
                val requiredByRatio = ceilPercent(comparableFrameCount, ratioPercent)
                return CompetitorMatchRule(
                    requiredMatches = maxOf(MIN_MATCHED_FRAME_COUNT + 1, requiredByRatio),
                    requiredSpan = MIN_MATCHED_FRAME_GAP
                )
            }

            private fun ceilPercent(value: Int, percent: Int): Int {
                return (value * percent + 99) / 100
            }
        }
    }

    private companion object {
        const val MIN_MATCHED_FRAME_COUNT = 2
        const val MIN_MATCHED_FRAME_GAP = 2
        const val VIDEO_MATCH_RATIO_PERCENT = 35
        const val SCREEN_RECORDING_MATCH_RATIO_PERCENT = 45
    }
}

enum class VideoFingerprintSource {
    SYSTEM_THUMBNAIL,
    HYBRID_THUMBNAIL_AND_FRAMES,
    MMR_FRAMES,
    COMPETITOR_FRAMES
}
