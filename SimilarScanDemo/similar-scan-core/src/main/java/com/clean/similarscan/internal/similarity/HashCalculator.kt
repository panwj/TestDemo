package com.clean.similarscan.internal.similarity

import android.graphics.Bitmap
import android.os.Build

/**
 * 计算图片组合指纹。
 *
 * imageHash 使用 Kotlin 双线性采样 dHash 实现；colorHash 使用 RGB 8x3 分桶算法。
 */
object HashCalculator {
    /** 从已经加载好的 Bitmap 生成结构指纹和颜色指纹。 */
    fun buildHash(source: Bitmap): CombinedHash {
        return CombinedHash(
            imageHash = KotlinDHash.fromBitmap(source),
            colorHash = colorHash(source)
        )
    }

    /**
     * RGB 颜色直方图：每个颜色通道按 32 为步长分为 8 桶。
     *
     * 除数使用像素数 / 16，使输出值落在更适合阈值比较的范围内。
     */
    private fun colorHash(source: Bitmap): Array<DoubleArray> {
        /*
         * Bitmap.getPixels() 会统一返回 ARGB int，不需要为了 RGB_565/硬件图等输入
         * 额外 copy 成 ARGB_8888。全量扫描 9k 图片时，这个拷贝会放大 Native 内存
         * 分配和 GC 压力；直接读取像素即可保持算法结果一致。
         */
        val bitmap = if (Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        for (pixel in pixels) {
            red[(pixel shr 16) and 0xFF]++
            green[(pixel shr 8) and 0xFF]++
            blue[pixel and 0xFF]++
        }

        val divisor = pixels.size / 16.0
        val hash = Array(8) { DoubleArray(3) }
        for (bucket in 0 until 8) {
            val start = bucket * 32
            val end = start + 32
            for (value in start until end) {
                hash[bucket][0] += red[value].toDouble()
                hash[bucket][1] += green[value].toDouble()
                hash[bucket][2] += blue[value].toDouble()
            }
            hash[bucket][0] /= divisor
            hash[bucket][1] /= divisor
            hash[bucket][2] /= divisor
        }
        if (bitmap !== source) bitmap.recycle()
        return hash
    }
}
