package com.clean.similarscan.internal.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.clean.similarscan.internal.model.MediaAsset
import com.clean.similarscan.internal.model.MediaKind
import com.clean.similarscan.internal.util.string
import com.clean.similarscan.internal.util.useCursor
import kotlin.math.max

/**
 * 缩略图加载器。
 *
 * 与参考规则一致：优先用系统 thumbnail；失败时再走 input stream，并用 inSampleSize 控制解码尺寸。
 */
class MediaBitmapLoader(private val resolver: ContentResolver) {
    /**
     * 加载用于生成扫描指纹的 Bitmap。
     *
     * 该方法只服务图片和截图。视频使用独立的 MediaMetadataRetriever 多帧流程。
     * 当前产品策略是“系统缩略图优先”：
     * 1. Android 10+ 先走 ContentResolver.loadThumbnail，请求目标尺寸由系统直接生成；
     * 2. Android 10+ loadThumbnail 失败后直接进入 uri/DATA 降采样解码，避免旧缩略图
     *    API 在新系统内部再次代理到 loadThumbnail，造成重复失败和重复耗时；
     * 3. Android 9 及以下再读 MediaStore.Images.Thumbnails，复用系统相册预生成缓存；
     * 4. 最后才自行通过 uri 或 DATA 路径 decode。
     */
    fun loadFingerprintBitmap(asset: MediaAsset, thumbSize: Int = 1024): Bitmap? {
        return loadFingerprintBitmapWithSource(asset, thumbSize)?.bitmap
    }

    /**
     * 加载图片/截图指纹 Bitmap，并记录实际来源。
     *
     * 来源统计会写入 ScanMetrics，用于判断真机扫描耗时主要来自系统缩略图、URI 解码还是
     * DATA 文件路径兜底。返回的 Bitmap 已经被压到 thumbSize 内，调用方使用完必须回收。
     */
    fun loadFingerprintBitmapWithSource(asset: MediaAsset, thumbSize: Int = 1024): FingerprintBitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                /*
                 * loadThumbnail 能让 MediaProvider 按请求尺寸返回缩略图，避免先取旧
                 * MINI_KIND 大图再二次缩放。Android 10+ 的旧 Thumbnails API 很多设备
                 * 内部仍会转调 loadThumbnail；如果这里失败，继续调用旧 API 往往只是
                 * 重复失败。因此新系统直接进入 URI/DATA 降采样兜底。
                 */
                return FingerprintBitmap(
                    normalizeFingerprintBitmap(
                        resolver.loadThumbnail(
                            asset.uri,
                            Size(thumbSize, thumbSize),
                            null
                        ),
                        thumbSize
                    ),
                    FingerprintBitmapSource.SYSTEM_LOAD_THUMBNAIL
                )
            } catch (_: Exception) {
                // 部分 MediaProvider/云端资源无法按 URI 加载缩略图，继续走解码兜底。
            }
        } else {
            legacyImageThumbnail(asset)?.let {
                return FingerprintBitmap(
                    normalizeFingerprintBitmap(it, thumbSize),
                    FingerprintBitmapSource.SYSTEM_MEDIASTORE_THUMBNAIL
                )
            }
        }
        decodeSampledBitmap(asset.uri, thumbSize)?.let {
            return FingerprintBitmap(normalizeFingerprintBitmap(it, thumbSize), FingerprintBitmapSource.DECODE_URI)
        }

        /*
         * 采用参考规则最后一层 fallback：ContentResolver 解码失败后，继续尝试 DATA
         * 真实路径。部分厂商 MediaProvider 或本地迁移资源只能通过文件路径读取。
         */
        val path = pathFromUri(asset.uri) ?: return null
        return decodeSampledBitmap(path, thumbSize)?.let {
            FingerprintBitmap(normalizeFingerprintBitmap(it, thumbSize), FingerprintBitmapSource.DECODE_FILE)
        }
    }

    /**
     * 加载 UI 预览图。
     *
     * 该方法不参与相似判断，只服务列表、详情和大图预览。视频封面为了贴近系统相册展示，
     * 优先走 0ms 同步关键帧；图片预览则优先使用系统缩略图。
     */
    fun loadBitmap(asset: MediaAsset, thumbSize: Int = 1024): Bitmap? {
        if (asset.kind == MediaKind.VIDEO || asset.kind == MediaKind.SCREEN_RECORDING) {
            /*
             * 经临时诊断页对照系统相册：系统视频封面基本等同于 0ms 关键帧，
             * 少量资源与 ThumbnailUtils 一致。因此正式展示优先使用
             * MediaMetadataRetriever 0ms closeSync，失败时再使用 ThumbnailUtils。
             */
            extractVideoFrameAtStart(asset)?.let { return it }
            return thumbnailUtilsVideo(asset)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return resolver.loadThumbnail(asset.uri, Size(thumbSize, thumbSize), null)
            } catch (_: Exception) {
                // 部分云端资源或权限受限资源可能无法直接加载缩略图，继续 fallback。
            }
        }
        return decodeSampledBitmap(asset.uri, thumbSize)
    }

    /**
     * Android 9 及以下系统相册主要依赖 MediaStore 预生成的视频缩略图。
     * UI 封面优先读取该结果，可以避免自行抽帧导致与系统封面不一致。
     */
    private fun legacyVideoThumbnail(asset: MediaAsset): Bitmap? {
        return try {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                resolver,
                asset.id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Android 9 及以下的图片系统缩略图读取入口。
     */
    private fun legacyImageThumbnail(asset: MediaAsset): Bitmap? {
        return try {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                resolver,
                asset.id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 使用首个同步关键帧作为视频封面，尽量贴近系统相册在多数录屏上的展示效果。
     */
    private fun extractVideoFrameAtStart(asset: MediaAsset): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(asset.uri, "r")?.use { fd ->
                retriever.setDataSource(fd.fileDescriptor)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 系统部分视频封面与 ThumbnailUtils 生成结果一致，作为 0ms 关键帧失败后的兜底。
     */
    private fun thumbnailUtilsVideo(asset: MediaAsset): Bitmap? {
        val path = pathFromUri(asset.uri) ?: return legacyVideoThumbnail(asset)
        return try {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                ?: legacyVideoThumbnail(asset)
        } catch (_: Exception) {
            legacyVideoThumbnail(asset)
        }
    }

    /**
     * 通过 content:// URI 解码 Bitmap，并用 inSampleSize 控制内存峰值。
     */
    private fun decodeSampledBitmap(uri: Uri, targetSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val sampleSize = calculateInSampleSize(bounds, targetSize, targetSize)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 DATA 文件路径解码 Bitmap，作为 URI 解码失败后的最后兜底。
     */
    private fun decodeSampledBitmap(path: String, targetSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, targetSize, targetSize)
            }
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 指纹计算不需要原始系统缩略图尺寸。这里把所有来源统一压到固定上限：
     * 1. dHash/colorHash 的输入稳定，避免不同来源尺寸差异影响耗时；
     * 2. 释放被替换的大图，减少 9k+ 资源全量扫描时的 Native Bitmap 压力；
     * 3. 使用等比缩放，不拉伸宽高比例，降低对相似判断结果的影响。
     */
    private fun normalizeFingerprintBitmap(bitmap: Bitmap, targetSize: Int): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= targetSize || maxSide <= 0) return bitmap

        val scale = targetSize.toFloat() / maxSide.toFloat()
        val targetWidth = max(1, (bitmap.width * scale).toInt())
        val targetHeight = max(1, (bitmap.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }

    /**
     * 计算 BitmapFactory 的 2 的幂采样值，避免解码出远大于目标尺寸的 Bitmap。
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    /**
     * 从 MediaStore 查询 DATA 文件路径。
     *
     * 分区存储环境下 DATA 可能为空或不可读，因此调用方只能把它作为兜底路径。
     */
    private fun pathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return resolver.query(uri, projection, null, null, null).useCursor { cursor ->
            if (cursor.moveToFirst()) cursor.string(MediaStore.MediaColumns.DATA) else null
        }
    }
}

/**
 * 指纹 Bitmap 及其来源。
 */
data class FingerprintBitmap(
    val bitmap: Bitmap,
    val source: FingerprintBitmapSource
)

/**
 * 指纹 Bitmap 来源，用于扫描指标统计和性能排查。
 */
enum class FingerprintBitmapSource {
    SYSTEM_MEDIASTORE_THUMBNAIL,
    SYSTEM_LOAD_THUMBNAIL,
    DECODE_URI,
    DECODE_FILE
}
