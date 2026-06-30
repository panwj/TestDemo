package com.clean.similarscan.internal.similarity

import android.graphics.Bitmap
import com.clean.similarscan.internal.model.MediaAsset
import kotlin.math.abs

/**
 * 本地媒体质量评分，用于生成参考式 Best 推荐。
 *
 * 评分综合清晰度、曝光、分辨率、收藏状态和编辑状态。它不上传图片，
 * 只在缩略图上采样计算，避免对大图库产生过高成本。
 */
object MediaQualityAnalyzer {
    fun score(bitmap: Bitmap, asset: MediaAsset): Double {
        /*
         * 质量分只需要稳定地比较同组图片谁更适合作为 Best，不需要遍历 1024x1024
         * 的全部像素。这里直接在已加载的指纹缩略图上按 64x64 网格采样：
         * 1. 不再额外 createScaledBitmap，减少一次缩放和 bitmap 分配；
         * 2. 计算量固定为 4096 个采样点，避免大图拖慢全量扫描；
         * 3. 采样覆盖整张图，清晰度/曝光排序仍然稳定。
         */
        val pixels = sampleLuminance(bitmap)
        var edgeEnergy = 0.0
        var luminanceSum = 0.0
        var clipped = 0
        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val offset = y * SAMPLE_SIZE + x
                val luminance = pixels[offset]
                luminanceSum += luminance
                if (luminance < 8 || luminance > 247) clipped++
                if (x > 0) {
                    edgeEnergy += abs(luminance - pixels[offset - 1])
                }
                if (y > 0) {
                    edgeEnergy += abs(luminance - pixels[offset - SAMPLE_SIZE])
                }
            }
        }

        val pixelCount = SAMPLE_SIZE * SAMPLE_SIZE
        val meanLuminance = luminanceSum / pixelCount
        val exposureScore = 1.0 - (abs(meanLuminance - 128.0) / 128.0)
        val clippingScore = 1.0 - clipped.toDouble() / pixelCount
        val sharpnessScore = (edgeEnergy / (pixelCount * 80.0)).coerceIn(0.0, 1.0)

        return sharpnessScore * 50.0 +
            exposureScore * 18.0 +
            clippingScore * 12.0 +
            metadataScore(asset)
    }

    /**
     * 不依赖 Bitmap 的排序分。
     *
     * 视频指纹帧只有 9x8，对它做清晰度/曝光分析没有意义；图片在 bitmap 读取失败或
     * 后续需要进一步降成本时，也可以用这部分作为 Best 排序的稳定兜底。
     */
    fun metadataScore(asset: MediaAsset): Double {
        val megapixels = asset.width.toDouble() * asset.height.toDouble() / 1_000_000.0
        val resolutionScore = (megapixels / 12.0).coerceIn(0.0, 1.0)
        return resolutionScore * 15.0 +
            if (asset.isFavorite) 8.0 else 0.0 +
            if (asset.isEdited) 3.0 else 0.0
    }

    private fun luminance(pixel: Int): Int {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun sampleLuminance(bitmap: Bitmap): IntArray {
        val result = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return result

        for (sampleY in 0 until SAMPLE_SIZE) {
            val sourceY = ((sampleY + 0.5) * height / SAMPLE_SIZE).toInt().coerceIn(0, height - 1)
            for (sampleX in 0 until SAMPLE_SIZE) {
                val sourceX = ((sampleX + 0.5) * width / SAMPLE_SIZE).toInt().coerceIn(0, width - 1)
                result[sampleY * SAMPLE_SIZE + sampleX] = luminance(bitmap.getPixel(sourceX, sourceY))
            }
        }
        return result
    }

    private const val SAMPLE_SIZE = 64
}
