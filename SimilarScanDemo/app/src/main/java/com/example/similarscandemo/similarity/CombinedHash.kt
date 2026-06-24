package com.example.similarscandemo.similarity

import com.example.similarscandemo.model.MediaKind
import kotlin.math.abs

/**
 * 组合指纹：结构相似用 dHash，颜色相似用 8x3 RGB 直方图。
 */
data class CombinedHash(
    val imageHash: Long,
    val colorHash: Array<DoubleArray>
) {
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
