package com.clean.similarscan.internal.similarity

import com.clean.similarscan.internal.model.MediaKind

/**
 * 视频视觉指纹。
 *
 * REFERENCE_COMPAT 模式会按视频时长抽取 7～13 个归一化时间位置的帧。
 * 抽帧失败也会保留无效占位，因此这里只要求帧列表非空；无效帧在比较时跳过。
 */
data class VideoFingerprint(
    /** 视频关键帧的组合指纹，顺序与抽帧时间顺序保持一致。 */
    val frames: List<CombinedHash>,
    /** 用于同组内 Best 排序的质量分，不参与相似判定。 */
    val qualityScore: Double = 0.0,
    /** 指纹来源决定比较策略，例如系统封面和多帧指纹不能完全按同一规则处理。 */
    val source: VideoFingerprintSource = if (frames.count(CombinedHash::isValid) <= 1) {
        VideoFingerprintSource.SYSTEM_THUMBNAIL
    } else {
        VideoFingerprintSource.MMR_FRAMES
    }
) {
    /** 至少存在一帧有效指纹时，视频才参与相似候选比较。 */
    fun isValid(): Boolean = frames.any(CombinedHash::isValid)

    /**
     * 判断两个视频是否相似。
     *
     * 系统封面只有单帧信息，只允许封面与封面之间比较；多帧指纹则使用帧级匹配数量
     * 和时间跨度约束，避免只因为片头、黑屏或静态页面相似就合并整段视频。
     */
    fun isSimilarTo(other: VideoFingerprint, kind: MediaKind): Boolean {
        if (!isValid() || !other.isValid()) return false

        if (source == VideoFingerprintSource.REFERENCE_FRAMES &&
            other.source == VideoFingerprintSource.REFERENCE_FRAMES
        ) {
            return isReferenceCompatibleSimilar(frames, other.frames, kind)
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
         * 多帧匹配会遍历抽出的帧组合，只要有足够多的帧相似即判定视频相似。
         * 当前实现以“至少两帧命中”的口径增加两个工程约束：
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

    /**
     * REFERENCE_COMPAT 的多帧比较。
     *
     * 该模式保留无效帧占位，比较时先生成所有可行帧配对，再按距离从近到远消费，
     * 确保一帧只匹配一次，并根据有效帧数量动态提高命中要求。
     */
    private fun isReferenceCompatibleSimilar(
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

        val rule = ReferenceMatchRule.forKind(kind, comparableFrameCount)
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
         * REFERENCE_COMPAT 仍然沿用 7～13 帧抽样，但不能只做“任意两帧命中”。
         * 真实录屏/视频里经常存在相似片头、黑屏、加载页或静态页面，如果允许同一候选帧
         * 被多次消费，或者命中都集中在开头几帧，就会把本应拆开的资源合成一个大组。
         *
         * 这里把比较升级为三层约束：
         * 1. 每个候选帧只能消费一次；
         * 2. 命中数量要达到有效帧比例要求；
         * 3. 多帧资源的命中位置要覆盖一定时间跨度。
         *
         * 这样不改变抽帧成本，也不影响真正重复视频的召回，但能让“明显还能继续细分”
         * 的大组在最终锚点分组时自然拆开。
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

            if (matchesReferenceRule(matchedFirstIndexes, matchedSecondIndexes, rule)) return true
        }
        return matchesReferenceRule(matchedFirstIndexes, matchedSecondIndexes, rule)
    }

    /** 普通多帧模式要求至少两帧命中，并且命中位置不能都挤在相邻帧。 */
    private fun hasEnoughSeparatedMatches(indexes: List<Int>): Boolean {
        if (indexes.size < MIN_MATCHED_FRAME_COUNT) return false
        val first = indexes.first()
        return indexes.any { kotlin.math.abs(it - first) >= MIN_MATCHED_FRAME_GAP }
    }

    /** 判断当前命中的帧数量和时间跨度是否满足动态规则。 */
    private fun matchesReferenceRule(
        firstIndexes: List<Int>,
        secondIndexes: List<Int>,
        rule: ReferenceMatchRule
    ): Boolean {
        if (firstIndexes.size < rule.requiredMatches) return false
        if (rule.requiredSpan <= 0) return true
        return frameSpan(firstIndexes) >= rule.requiredSpan &&
            frameSpan(secondIndexes) >= rule.requiredSpan
    }

    /** 计算命中帧覆盖的索引跨度，用于过滤只在局部片段相似的资源。 */
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

    private data class ReferenceMatchRule(
        val requiredMatches: Int,
        val requiredSpan: Int
    ) {
        companion object {
            /**
             * 根据可比较帧数量生成动态命中规则。
             *
             * 帧越多，要求的命中比例越高；录屏类内容静态画面更多，因此使用更严格比例。
             */
            fun forKind(kind: MediaKind, comparableFrameCount: Int): ReferenceMatchRule {
                if (comparableFrameCount <= 2) {
                    return ReferenceMatchRule(requiredMatches = 1, requiredSpan = 0)
                }
                if (comparableFrameCount <= 5) {
                    return ReferenceMatchRule(requiredMatches = 2, requiredSpan = 1)
                }

                val ratioPercent = if (kind == MediaKind.SCREEN_RECORDING) {
                    SCREEN_RECORDING_MATCH_RATIO_PERCENT
                } else {
                    VIDEO_MATCH_RATIO_PERCENT
                }
                val requiredByRatio = ceilPercent(comparableFrameCount, ratioPercent)
                return ReferenceMatchRule(
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

/** 视频指纹来源，用于选择后续相似比较策略。 */
enum class VideoFingerprintSource {
    /** 仅使用系统媒体库封面生成的单帧指纹。 */
    SYSTEM_THUMBNAIL,
    /** 系统封面加少量 MediaMetadataRetriever 抽帧的混合指纹。 */
    HYBRID_THUMBNAIL_AND_FRAMES,
    /** 完全由 MediaMetadataRetriever 抽帧生成的多帧指纹。 */
    MMR_FRAMES,
    /** 使用固定间隔规则生成的多帧指纹，适合稳定复验相似视频结果。 */
    REFERENCE_FRAMES
}
