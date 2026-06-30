package com.clean.similarscan.internal.similarity

import com.clean.similarscan.internal.model.MediaKind
import kotlin.math.abs

/**
 * 组合指纹：结构相似用 dHash，颜色相似用 8x3 RGB 直方图。
 */
data class CombinedHash(
    /** 64 位 dHash，描述图片或视频帧的明暗结构变化。 */
    val imageHash: Long,
    /** RGB 8x3 颜色直方图，补充 dHash 对颜色差异不敏感的问题。 */
    val colorHash: Array<DoubleArray>
) {
    /** 指纹生成失败时使用 -1 和空颜色数组表示，避免异常中断扫描链路。 */
    fun isValid(): Boolean = imageHash != -1L && colorHash.isNotEmpty()

    /**
     * 完全重复使用严格指纹判断。
     *
     * 同一张图被复制后，MediaStore id 不同，但 dHash 和 colorHash 会保持一致；
     * 这类结果应进入“相同图片”，不和普通相似图混在一起。
     */
    fun isDuplicateOf(other: CombinedHash): Boolean {
        return imageHash == other.imageHash && colorDistanceTo(other) == 0L
    }

    /**
     * 按媒体类型阈值判断视觉相似。
     *
     * 先用 dHash 汉明距离做快速筛选；处于中间距离区间的候选，再叠加 colorHash
     * 距离过滤，减少不同内容但结构接近的误判。
     */
    fun isSimilarTo(other: CombinedHash, kind: MediaKind): Boolean {
        val distance = java.lang.Long.bitCount(imageHash xor other.imageHash).toLong()
        val threshold = Threshold.forKind(kind)
        if (distance >= threshold.maxImageDistanceExclusive) return false
        if (threshold.directImageDistance.contains(distance)) return true

        val colorDistance = colorDistanceTo(other)
        return threshold.colorRanges.any { range ->
            range.imageDistance.contains(distance) && range.colorDistance.contains(colorDistance)
        }
    }

    /** 计算两个 RGB 8x3 颜色直方图的曼哈顿距离。 */
    fun colorDistanceTo(other: CombinedHash): Long {
        var distance = 0.0
        for (i in colorHash.indices) {
            for (j in colorHash[i].indices) {
                distance += abs(colorHash[i][j] - other.colorHash[i][j])
            }
        }
        return distance.toLong()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CombinedHash) return false
        return imageHash == other.imageHash && colorHash.contentDeepEquals(other.colorHash)
    }

    override fun hashCode(): Int {
        return 31 * imageHash.hashCode() + colorHash.contentDeepHashCode()
    }
}
