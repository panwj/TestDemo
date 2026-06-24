package com.example.similarscandemo.similarity

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin dHash 实现。
 *
 * 该实现移植自项目根目录的 DHash.kt，用于替换 Demo 原先依赖
 * Bitmap.createScaledBitmap() 的实现。它直接在原图上对 9x8 网格执行双线性采样，
 * 从而避免 Android Bitmap 缩放器在不同系统版本上的插值差异。
 */
object KotlinDHash {
    private const val SAMPLE_COLUMNS = 9
    private const val SAMPLE_ROWS = 8
    private const val MAX_DIMENSION = 16_384

    /**
     * 返回 64 位 dHash。输入无效时返回 -1L，与竞品 native 的错误值保持一致。
     */
    fun fromBitmap(bitmap: Bitmap?): Long {
        if (bitmap == null) return -1L

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return -1L
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return -1L

        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return -1L
            } catch (_: Throwable) {
                return -1L
            }
        }

        return try {
            computeDHash(source)
        } finally {
            if (source !== bitmap && !source.isRecycled) {
                /*
                 * 与根目录 DHash.kt 保持一致：不在算法内部 recycle 临时副本，
                 * 避免 Bitmap 生命周期处理与参考实现产生差异。
                 */
            }
        }
    }

    /** 计算两个 dHash 的汉明距离。 */
    fun hammingDistance(first: Long, second: Long): Int {
        return java.lang.Long.bitCount(first xor second)
    }

    private fun computeDHash(bitmap: Bitmap): Long {
        val width = bitmap.width
        val height = bitmap.height
        val row = DoubleArray(SAMPLE_COLUMNS)
        var hash = 0L

        for (yIndex in 0 until SAMPLE_ROWS) {
            val y = (height - 1).toDouble() * yIndex / (SAMPLE_ROWS - 1)

            for (xIndex in 0 until SAMPLE_COLUMNS) {
                val x = (width - 1).toDouble() * xIndex / (SAMPLE_COLUMNS - 1)
                row[xIndex] = sampleGrayBilinear(bitmap, x, y)
            }

            for (xIndex in 0 until SAMPLE_COLUMNS - 1) {
                hash = (hash shl 1) or if (row[xIndex] > row[xIndex + 1]) 1L else 0L
            }
        }
        return hash
    }

    /**
     * 对采样点周围四个像素的灰度值做双线性插值。
     */
    private fun sampleGrayBilinear(bitmap: Bitmap, x: Double, y: Double): Double {
        val clampedX = clampCoordinate(x, bitmap.width)
        val clampedY = clampCoordinate(y, bitmap.height)

        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = min(x0 + 1, bitmap.width - 1)
        val y1 = min(y0 + 1, bitmap.height - 1)
        val dx = clampedX - x0
        val dy = clampedY - y0

        val top = gray(bitmap.getPixel(x0, y0)) * (1.0 - dx) +
            gray(bitmap.getPixel(x1, y0)) * dx
        val bottom = gray(bitmap.getPixel(x0, y1)) * (1.0 - dx) +
            gray(bitmap.getPixel(x1, y1)) * dx
        return top * (1.0 - dy) + bottom * dy
    }

    private fun clampCoordinate(value: Double, size: Int): Double {
        if (size <= 1) return 0.0
        return max(0.0, min(value, (size - 1).toDouble()))
    }

    private fun gray(argb: Int): Double {
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }
}
