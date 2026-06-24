package com.example.similarscandemo.similarity

import android.graphics.Bitmap
import com.example.similarscandemo.model.MediaAsset
import kotlin.math.abs

/**
 * 本地媒体质量评分，用于生成竞品式 Best 推荐。
 *
 * 评分综合清晰度、曝光、分辨率、收藏状态和编辑状态。它不上传图片，
 * 只在缩略图上采样计算，避免对大图库产生过高成本。
 */
object MediaQualityAnalyzer {
    fun score(bitmap: Bitmap, asset: MediaAsset): Double {
        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        return try {
            var edgeEnergy = 0.0
            var luminanceSum = 0.0
            var clipped = 0
            for (y in 0 until SAMPLE_SIZE) {
                for (x in 0 until SAMPLE_SIZE) {
                    val luminance = luminance(sample.getPixel(x, y))
                    luminanceSum += luminance
                    if (luminance < 8 || luminance > 247) clipped++
                    if (x > 0) {
                        edgeEnergy += abs(luminance - luminance(sample.getPixel(x - 1, y)))
                    }
                    if (y > 0) {
                        edgeEnergy += abs(luminance - luminance(sample.getPixel(x, y - 1)))
                    }
                }
            }

            val pixelCount = SAMPLE_SIZE * SAMPLE_SIZE
            val meanLuminance = luminanceSum / pixelCount
            val exposureScore = 1.0 - (abs(meanLuminance - 128.0) / 128.0)
            val clippingScore = 1.0 - clipped.toDouble() / pixelCount
            val sharpnessScore = (edgeEnergy / (pixelCount * 80.0)).coerceIn(0.0, 1.0)
            val megapixels = asset.width.toDouble() * asset.height.toDouble() / 1_000_000.0
            val resolutionScore = (megapixels / 12.0).coerceIn(0.0, 1.0)

            sharpnessScore * 50.0 +
                exposureScore * 18.0 +
                clippingScore * 12.0 +
                resolutionScore * 15.0 +
                if (asset.isFavorite) 8.0 else 0.0 +
                if (asset.isEdited) 3.0 else 0.0
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private const val SAMPLE_SIZE = 64
}
